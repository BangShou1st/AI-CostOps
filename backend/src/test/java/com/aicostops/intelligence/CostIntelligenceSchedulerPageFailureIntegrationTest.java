package com.aicostops.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.intelligence.application.CostIntelligenceAnalysisService;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real-MySQL regressions for the scheduler keyset paging recovery (P1/P2):
 * a transient failure of a page query must neither skip the organizations the
 * failed page would have returned (P2: the old recovery probe advanced the
 * cursor past a FULL page without processing it) nor rewind to earlier pages
 * and reprocess them while the short final page is never reached (P1: the old
 * probe returned -1, which was re-used as a cursor back onto the first page
 * and reset the failure bound). The failure is injected on a delegating proxy
 * of the real {@link CostFactsMapper} so the production {@code runDueAnalyses}
 * control flow runs against the real {@code recentlyActiveOrgs} query; the
 * pure unit counterpart is CostIntelligenceSchedulerPagingRecoveryTest.
 */
@SpringBootTest
@Tag("integration")
class CostIntelligenceSchedulerPageFailureIntegrationTest
        extends AuthenticationContainersSupport {

    /** More active organizations than one scheduler page, mirroring the defects' repro. */
    private static final int ACTIVE_ORGS = 101;

    /**
     * Publishes a fault-injectable front for the real MyBatis mapper: every
     * unstubbed call delegates to the real bean, so unstubbed lookups (e.g.
     * settledCurrencies) keep their real behavior while tests stub only the
     * page fetch. A spy cannot be used here because a MyBatis mapper is a
     * final-behavior interface proxy with no callable real methods.
     */
    @TestConfiguration
    static class FaultInjectableFactsConfig {
        @Bean
        @Primary
        CostFactsMapper faultInjectableFacts(CostFactsMapper real) {
            return mock(CostFactsMapper.class, AdditionalAnswers.delegatesTo(real));
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CostIntelligenceAnalysisService analysis;
    @Autowired CostFactsMapper facts;
    @Autowired @Qualifier("costFactsMapper") CostFactsMapper realFacts;

    /**
     * {@code @EnableScheduling} fires the {@link com.aicostops.intelligence.application.CostIntelligenceScheduler}
     * sweep once at context startup, which would inject an extra page query into
     * whichever test happens to be running. Mocking the thin scheduler wrapper
     * keeps every {@code recentlyActiveOrgs} invocation attributable to the
     * test's own sweep; the service under test is untouched.
     */
    @MockitoBean com.aicostops.intelligence.application.CostIntelligenceScheduler backgroundScheduler;

    private final List<Long> createdOrgs = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final AtomicInteger pageQueries = new AtomicInteger();
    private final List<Long> pageQueryCursors = new ArrayList<>();

    @BeforeEach
    void seedActiveOrganizations() {
        // The shared container may hold coverage rows from other test classes;
        // clear them so the scheduler scan sees exactly this test's seed.
        jdbc.update("DELETE FROM cost_intelligence_run");
        jdbc.update("DELETE FROM cost_history_coverage");
        jdbc.update("DELETE FROM cost_history_coverage_revision");

        for (int i = 0; i < ACTIVE_ORGS; i++) {
            insertActiveOrg("active-" + i);
        }
    }

    @AfterEach
    void cleanup() {
        org.mockito.Mockito.reset(facts);
        for (var orgId : createdOrgs) {
            jdbc.update("DELETE FROM savings_recommendation WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_forecast_snapshot WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_anomaly WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_intelligence_run WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_history_coverage WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM organization WHERE id=?", orgId);
        }
        for (var userId : userIds) {
            jdbc.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }

    /**
     * P2: the FIRST page query fails transiently, the retry succeeds. The full
     * first page must be processed before the cursor advances, so every one of
     * the 101 organizations ends up with exactly one COMPLETED run, and the
     * round needed exactly three page queries (fail + page 1 + page 2).
     */
    @Test
    void fullPageTransientFailureStillProcessesEveryOrganizationOnce() {
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            pageQueryCursors.add(invocation.getArgument(1, Long.class));
            if (pageQueries.incrementAndGet() == 1) {
                throw new IllegalStateException("transient first page failure");
            }
            return delegatePageFetch(invocation);
        });

        analysis.runDueAnalyses();

        assertThat(completedRuns()).isEqualTo(ACTIVE_ORGS);
        assertThat(pageQueryCursors).as("page query cursors").hasSize(3);
        // fail + successful retry of the SAME page, then the short final page.
        assertThat(pageQueryCursors.get(0)).isEqualTo(pageQueryCursors.get(1));
        verify(facts, times(3)).recentlyActiveOrgs(any(), anyLong(), anyInt());
    }

    /**
     * P1: the LAST (short) page query fails transiently, the retry succeeds in
     * place. The round must not rewind to the first page (no duplicate runs)
     * and must not lose the short page's organization (no missing run).
     */
    @Test
    void lastShortPageTransientFailureResumesInPlaceWithoutRewind() {
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            pageQueryCursors.add(invocation.getArgument(1, Long.class));
            if (pageQueries.incrementAndGet() == 2) {
                throw new IllegalStateException("transient last page failure");
            }
            return delegatePageFetch(invocation);
        });

        analysis.runDueAnalyses();

        assertThat(completedRuns()).as("completed runs").isEqualTo(ACTIVE_ORGS);
        assertThat(pageQueryCursors).as("page query cursors").hasSize(3);
        // page 1, failed page 2, in-place retry of page 2 at the SAME cursor:
        // no rewind to the first page, no advance past unprocessed organizations.
        assertThat(pageQueryCursors.get(0)).isZero();
        assertThat(pageQueryCursors.get(1)).isEqualTo(pageQueryCursors.get(2));
        assertThat(pageQueryCursors.get(2)).isNotZero();
        verify(facts, times(3)).recentlyActiveOrgs(any(), anyLong(), anyInt());
    }

    /**
     * A persistently unavailable datasource must end the round after exactly
     * four page queries (1 initial + 3 retries of the same page) with no run
     * rows, instead of looping or skipping ahead.
     */
    @Test
    void persistentDatasourceFailureAbandonsRoundAfterBoundedAttempts() {
        when(facts.recentlyActiveOrgs(any(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("storage unavailable"));

        analysis.runDueAnalyses();

        verify(facts, times(4)).recentlyActiveOrgs(any(), anyLong(), anyInt());
        assertThat(completedRuns()).isZero();
    }

    // -- helpers ---------------------------------------------------------------

    private List<Long> delegatePageFetch(org.mockito.invocation.InvocationOnMock invocation) {
        return realFacts.recentlyActiveOrgs(invocation.getArgument(0, LocalDate.class),
                invocation.getArgument(1, Long.class), invocation.getArgument(2, Integer.class));
    }

    private int completedRuns() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_intelligence_run WHERE status='COMPLETED'",
                Integer.class);
    }

    private void insertActiveOrg(String label) {
        var slug = "sched-page-failure-" + label + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at) "
                + "VALUES('Scheduler page failure test',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                slug);
        var orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
        createdOrgs.add(orgId);
        jdbc.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,"
                        + "created_at,updated_at) VALUES (?, 'Traversal Fixture','ACTIVE',0,"
                        + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "pagefail-" + slug + "@example.test");
        var reviewer = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?", Long.class,
                "pagefail-" + slug + "@example.test");
        userIds.add(reviewer);
        jdbc.update("""
                INSERT INTO cost_history_coverage(org_id,currency,scope_type,scope_id,posting_day,
                  status,basis,review_note,ledger_entry_count,ledger_max_entry_id,
                  reviewed_by_user_id,updated_at)
                VALUES (?, 'CNY', 'ORGANIZATION', ?, ?, 'REVIEWED_COMPLETE', 'MANUAL_LEDGER_REVIEW',
                  'page failure fixture', 0, 0, ?, UTC_TIMESTAMP(6))
                """, orgId, orgId, LocalDate.now(Clock.systemUTC()).minusDays(1), reviewer);
    }
}
