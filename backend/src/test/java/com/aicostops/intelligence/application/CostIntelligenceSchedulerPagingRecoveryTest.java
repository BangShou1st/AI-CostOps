package com.aicostops.intelligence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure unit regression tests for the scheduler's keyset paging recovery in
 * {@link CostIntelligenceAnalysisService#runDueAnalyses()}, freezing the two
 * reported defects before the fix:
 *
 * <p>P1 — a transient failure on a short (final) page used a recovery probe
 * whose {@code -1} return was re-used as a cursor, rescanning the first page
 * and resetting the consecutive-failure bound, so the first 100 organizations
 * were revisited and the round could loop; P2 — a transient failure on the
 * first page used a recovery probe that advanced the cursor past a FULL page
 * without ever processing the 100 organizations it read.
 *
 * <p>The fixed contract frozen here: on a page-fetch failure the cursor stays
 * unchanged; the same page is retried in place (1 initial + 3 retries = at
 * most 4 queries); a successful fetch processes every organization it returned
 * through the normal path; only a fully processed page advances the cursor; a
 * short page ends the round; persistent failure abandons the round with WARN.
 * The production control flow under test is the real {@code runDueAnalyses}
 * loop — only the mapper boundary and the per-run service are mocked.
 */
class CostIntelligenceSchedulerPagingRecoveryTest {

    private static final int PAGE_SIZE = 100; // mirrors SCHEDULER_ORG_PAGE_SIZE
    private static final LocalDate SINCE = LocalDate.of(2026, 9, 14);

    private CostFactsMapper facts;
    private CostIntelligenceRunService runs;
    private CostIntelligenceAnalysisService service;
    /** Organization ids whose analysis run was actually started, in order. */
    private final List<Long> processed = new ArrayList<>();

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logWatcher;
    private ch.qos.logback.classic.Logger serviceLogger;

    @BeforeEach
    void setUp() {
        facts = mock(CostFactsMapper.class);
        runs = mock(CostIntelligenceRunService.class);
        when(runs.run(anyLong(), any(LocalDate.class), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    processed.add(invocation.getArgument(0, Long.class));
                    return null; // outcome is discarded by the scheduler
                });
        when(facts.settledCurrencies(anyLong())).thenReturn(List.of("CNY"));
        service = new CostIntelligenceAnalysisService(
                mock(AuthorizationContextService.class), facts,
                mock(IntelligenceMapper.class), mock(ObjectMapper.class),
                Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC),
                runs, mock(SavingsRecommendationGenerator.class));

        serviceLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CostIntelligenceAnalysisService.class);
        logWatcher = new ch.qos.logback.core.read.ListAppender<>();
        logWatcher.start();
        serviceLogger.addAppender(logWatcher);
        serviceLogger.setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logWatcher);
    }

    /**
     * P2 regression: the FIRST page query fails transiently. The retry must
     * re-read the same page, and every one of the 100 organizations it returns
     * must be processed before the cursor advances — none may be skipped by a
     * probe that jumps past a full page. 101 organizations total; the second
     * page is the short final one.
     */
    @Test
    void fullPageTransientFailureProcessesEveryOrganizationExactlyOnce() {
        var feed = new PageFeed()
                .fail(0L)
                .serve(0L, orgs(1, 100))
                .serve(100L, orgs(101, 101));
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(feed);

        service.runDueAnalyses();

        assertThat(processed).containsExactlyElementsOf(orgs(1, 101));
        assertThat(feed.cursors()).containsExactly(0L, 0L, 100L);
        assertThat(feed.requestedCount()).isEqualTo(3);
        assertThat(warnsContaining("page fetch failed")).hasSize(1);
        assertThat(warnsContaining("abandoned")).isEmpty();
    }

    /**
     * P1 regression: the FIRST page succeeds and the LAST (short) page query
     * fails transiently. The retry must re-read the same final page in place:
     * the first 100 organizations must not be revisited, org 101 must not be
     * lost, and the round must end after the short page.
     */
    @Test
    void lastShortPageTransientFailureResumesInPlaceWithoutRescanning() {
        var feed = new PageFeed()
                .serve(0L, orgs(1, 100))
                .fail(100L)
                .serve(100L, orgs(101, 101));
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(feed);

        service.runDueAnalyses();

        assertThat(processed).containsExactlyElementsOf(orgs(1, 101));
        assertThat(feed.cursors()).containsExactly(0L, 100L, 100L);
        assertThat(feed.requestedCount()).isEqualTo(3);
        assertThat(warnsContaining("page fetch failed")).hasSize(1);
        assertThat(warnsContaining("abandoned")).isEmpty();
    }

    /**
     * A persistently unavailable datasource must end the round after at most
     * 4 queries for the same page (1 initial + 3 retries) — never loop and
     * never advance the cursor past unprocessed organizations.
     */
    @Test
    void persistentDatasourceFailureAbandonsRoundAfterBoundedAttempts() {
        var feed = new PageFeed().fail(0L).fail(0L).fail(0L).fail(0L);
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(feed);

        service.runDueAnalyses();

        assertThat(processed).isEmpty();
        assertThat(feed.cursors()).containsExactly(0L, 0L, 0L, 0L);
        assertThat(feed.requestedCount()).isEqualTo(4);
        verify(facts, times(4)).recentlyActiveOrgs(any(), anyLong(), anyInt());
        assertThat(warnsContaining("page fetch failed")).hasSize(4);
        var abandoned = warnsContaining("abandoned");
        assertThat(abandoned).hasSize(1);
        assertThat(abandoned.getFirst().getFormattedMessage())
                .contains("organization 0");
    }

    /**
     * A failing per-organization settledCurrencies lookup stays confined to
     * that organization, including when it happens on the first page of a
     * multi-page round: the remaining organizations and the next page are
     * still processed.
     */
    @Test
    void settledCurrenciesFailureIsConfinedToItsOrganizationAcrossPages() {
        var feed = new PageFeed()
                .serve(0L, orgs(1, 100))
                .serve(100L, orgs(101, 101));
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(feed);
        when(facts.settledCurrencies(eq(5L)))
                .thenThrow(new IllegalStateException("currency lookup storage unavailable"));

        service.runDueAnalyses();

        assertThat(processed).containsExactlyElementsOf(orgs(1, 101).stream()
                .filter(orgId -> orgId != 5L).toList());
        var lookupWarns = warnsContaining("currency lookup failed");
        assertThat(lookupWarns).hasSize(1);
        assertThat(lookupWarns.getFirst().getFormattedMessage())
                .contains("organization 5");
        assertThat(warnsContaining("abandoned")).isEmpty();
    }

    /**
     * A repeated scheduled round walks the same deterministic feed again; the
     * durable run-record convergence of repeated rounds is proven separately
     * against real MySQL (CostIntelligenceSchedulerTraversalIntegrationTest).
     */
    @Test
    void repeatedRoundsTraverseTheFullDeterministicFeed() {
        var feed = new PageFeed()
                .serve(0L, orgs(1, 100))
                .serve(100L, orgs(101, 101))
                .serve(0L, orgs(1, 100))
                .serve(100L, orgs(101, 101));
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(feed);

        service.runDueAnalyses();
        service.runDueAnalyses();

        var expected = orgs(1, 101);
        assertThat(processed).hasSize(2 * expected.size());
        assertThat(processed.subList(0, expected.size()))
                .containsExactlyElementsOf(expected);
        assertThat(processed.subList(expected.size(), processed.size()))
                .containsExactlyElementsOf(expected);
        assertThat(feed.cursors()).containsExactly(0L, 100L, 0L, 100L);
    }

    // -- helpers ---------------------------------------------------------------

    private static List<Long> orgs(int firstInclusive, int lastInclusive) {
        var ids = new ArrayList<Long>();
        for (int id = firstInclusive; id <= lastInclusive; id++) {
            ids.add((long) id);
        }
        return ids;
    }

    private List<ch.qos.logback.classic.spi.ILoggingEvent> warnsContaining(String fragment) {
        return logWatcher.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(fragment))
                .toList();
    }

    /**
     * Scripted mapper boundary: each cursor carries a FIFO of behaviors — a
     * {@code List<Long>} page result or a {@link RuntimeException} failure.
     * A query for a cursor with an exhausted script fails the test, proving
     * the round terminated (or never advanced where it must not).
     */
    private static final class PageFeed implements Answer<List<Long>> {
        private final Map<Long, Deque<Object>> script = new HashMap<>();
        private final List<Long> cursors = new ArrayList<>();

        PageFeed serve(long afterOrgId, List<Long> page) {
            script.computeIfAbsent(afterOrgId, key -> new ArrayDeque<>()).add(page);
            return this;
        }

        PageFeed fail(long afterOrgId) {
            script.computeIfAbsent(afterOrgId, key -> new ArrayDeque<>())
                    .add(new IllegalStateException("transient page fetch failure"));
            return this;
        }

        List<Long> cursors() {
            return cursors;
        }

        int requestedCount() {
            return cursors.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Long> answer(InvocationOnMock invocation) {
            assertThat(invocation.getArgument(0, LocalDate.class)).isEqualTo(SINCE);
            assertThat(invocation.getArgument(2, Integer.class)).isEqualTo(PAGE_SIZE);
            long cursor = invocation.getArgument(1, Long.class);
            cursors.add(cursor);
            var behaviors = script.get(cursor);
            assertThat(behaviors)
                    .as("no scripted page query remains at cursor %s", cursor)
                    .isNotNull();
            assertThat(behaviors).as("script exhausted at cursor %s", cursor).isNotEmpty();
            var next = behaviors.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (List<Long>) next;
        }
    }
}
