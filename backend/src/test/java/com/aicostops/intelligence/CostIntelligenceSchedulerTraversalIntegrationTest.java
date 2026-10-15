package com.aicostops.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.intelligence.application.CostIntelligenceAnalysisService;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduler organization coverage with more organizations than one page: the
 * round must walk every recently active organization via the deterministic
 * keyset feed, without a gap and without a duplicate. Activity is seeded through
 * the real union sources (coverage stamps, settled gateway settlements and posted
 * ledger lineage), so deduplication and the out-of-window exclusion are exercised
 * against the same query the scheduler uses.
 */
@SpringBootTest
@Tag("integration")
class CostIntelligenceSchedulerTraversalIntegrationTest extends AuthenticationContainersSupport {

    /** Deliberately more active organizations than a single scheduler page. */
    private static final int ACTIVE_ORGS = 103;
    /** Mirrors CostIntelligenceAnalysisService.SCHEDULER_ORG_PAGE_SIZE. */
    private static final int PAGE_SIZE = 100;

    @Autowired JdbcTemplate jdbc;
    @Autowired CostFactsMapper facts;
    @Autowired CostIntelligenceAnalysisService analysis;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<Long> activeOrgs = new ArrayList<>();
    private final List<Long> multiSourceOrgs = new ArrayList<>();
    private final List<Long> createdOrgs = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private long inactiveOrg;
    private long multiSourceOrg;
    private long multiSourceInactiveOrg;

    @BeforeEach
    void seedActiveOrganizations() {
        activeOrgs.clear();
        multiSourceOrgs.clear();
        createdOrgs.clear();
        userIds.clear();
        // The shared container may hold coverage rows from other test classes;
        // clear them so the scheduler scan sees exactly this test's seed.
        jdbc.update("DELETE FROM cost_history_coverage");
        jdbc.update("DELETE FROM cost_history_coverage_revision");

        // One coverage-only organization whose stamp is older than the 30-day
        // activity window: the contrast for the window boundary.
        inactiveOrg = insertActiveOrg("inactive-coverage");
        stampCoverage(inactiveOrg, LocalDate.now(Clock.systemUTC()).minusDays(1), true);

        for (int i = 0; i < ACTIVE_ORGS; i++) {
            var orgId = insertActiveOrg("active-" + i);
            stampCoverage(orgId, LocalDate.now(Clock.systemUTC()).minusDays(1), false);
            activeOrgs.add(orgId);
        }

        // The same organization active through all three union sources (coverage
        // + settled gateway settlement + posted ledger lineage) must appear once.
        multiSourceOrg = insertActiveOrg("multi-source");
        stampCoverage(multiSourceOrg, LocalDate.now(Clock.systemUTC()).minusDays(1), false);
        seedSettlementAndLedger(multiSourceOrg, false);
        multiSourceOrgs.add(multiSourceOrg);

        // A second organization active through all three sources but entirely
        // outside the window must be excluded.
        multiSourceInactiveOrg = insertActiveOrg("multi-source-stale");
        stampCoverage(multiSourceInactiveOrg, LocalDate.now(Clock.systemUTC()).minusDays(1), true);
        seedSettlementAndLedger(multiSourceInactiveOrg, true);
        multiSourceOrgs.add(multiSourceInactiveOrg);
    }

    @AfterEach
    void cleanup() {
        // Child-before-parent per organization, so partial seeding cannot leak
        // rows that break later cleanup or other suites in the shared container.
        for (var orgId : createdOrgs) {
            jdbc.update("DELETE FROM savings_recommendation WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_forecast_snapshot WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_anomaly WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_intelligence_run WHERE org_id=?", orgId);
            // ledger_entry references gateway_settlement and ledger_posting, so
            // entries go before both.
            jdbc.update("DELETE FROM ledger_entry WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM gateway_settlement WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM ledger_posting WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_history_coverage WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM organization WHERE id=?", orgId);
        }
        for (var userId : userIds) {
            jdbc.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }

    /**
     * The deterministic keyset feed must return every recently active
     * organization exactly once even when there are more organizations than a
     * single page holds: the first page is full and a later page carries the
     * remainder.
     */
    @Test
    void keysetFeedWalksEveryActiveOrganizationOnceAcrossThePageBoundary() {
        var since = since();
        var feed = new ArrayList<Long>();
        var firstPage = facts.recentlyActiveOrgs(since, 0L, PAGE_SIZE);
        feed.addAll(firstPage);
        // A full first page proves the walk continues beyond the single page the
        // old fixed LIMIT 100 could ever return.
        assertThat(firstPage).hasSize(PAGE_SIZE);

        var afterOrgId = firstPage.getLast();
        while (firstPage.size() == PAGE_SIZE) {
            firstPage = facts.recentlyActiveOrgs(since, afterOrgId, PAGE_SIZE);
            feed.addAll(firstPage);
            if (!firstPage.isEmpty()) {
                afterOrgId = firstPage.getLast();
            }
        }

        assertThat(feed).containsAll(activeOrgs);
        assertThat(new HashSet<>(feed)).as("keyset pages never repeat an organization")
                .hasSameSizeAs(feed);
        assertThat(feed).doesNotContain(inactiveOrg);
        assertThat(feed.stream().filter(activeOrgs::contains).count()).isEqualTo(ACTIVE_ORGS);
    }

    /**
     * The activity union deduplicates one organization that is active through
     * all three real sources, and the window filter excludes an organization
     * whose every source stamp predates the window.
     */
    @Test
    void activityUnionDeduplicatesRealMultiSourceOrganizationAndExcludesStaleOne() {
        var feed = collectFeed();

        assertThat(feed.stream().filter(orgId -> orgId == multiSourceOrg).count())
                .as("coverage + settlement + ledger lineage deduplicate to one entry")
                .isEqualTo(1);
        assertThat(feed).doesNotContain(multiSourceInactiveOrg);
    }

    /**
     * The scheduled sweep must analyze every recently active organization — more
     * than one page — and a repeated round converges on the existing run
     * identity instead of re-analyzing.
     */
    @Test
    void scheduledSweepCoversEveryActiveOrganizationAndConvergesOnRepeat() {
        analysis.runDueAnalyses();

        for (var orgId : activeOrgs) {
            assertThat(completedRunCount(orgId))
                    .as("organization %s must have exactly one completed run", orgId)
                    .isEqualTo(1);
        }
        assertThat(completedRunCount(inactiveOrg)).isZero();
        assertThat(completedRunCount(multiSourceInactiveOrg)).isZero();

        analysis.runDueAnalyses();

        for (var orgId : activeOrgs) {
            assertThat(completedRunCount(orgId))
                    .as("a repeated round must converge on the existing run identity")
                    .isEqualTo(1);
        }
    }

    // -- helpers ---------------------------------------------------------------

    private LocalDate since() {
        // Mirrors the scheduler round window: yesterday - 30 days.
        return LocalDate.now(Clock.systemUTC()).minusDays(31);
    }

    private List<Long> collectFeed() {
        var feed = new ArrayList<Long>();
        var afterOrgId = 0L;
        List<Long> page;
        do {
            page = facts.recentlyActiveOrgs(since(), afterOrgId, PAGE_SIZE);
            feed.addAll(page);
            if (!page.isEmpty()) {
                afterOrgId = page.getLast();
            }
        } while (page.size() == PAGE_SIZE);
        return feed;
    }

    private int completedRunCount(long orgId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_intelligence_run WHERE org_id=? AND status='COMPLETED'",
                Integer.class, orgId);
    }

    private long insertActiveOrg(String label) {
        var slug = "sched-traversal-" + label + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at) "
                + "VALUES('Scheduler traversal test',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", slug);
        var orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
        createdOrgs.add(orgId);
        jdbc.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,"
                        + "created_at,updated_at) VALUES (?, 'Traversal Fixture','ACTIVE',0,"
                        + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "traversal-" + slug + "@example.test");
        userIds.add(jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?", Long.class,
                "traversal-" + slug + "@example.test"));
        return orgId;
    }

    /**
     * Coverage activity stamp. {@code stale} places {@code updated_at} older than
     * the 30-day window; otherwise it is fresh.
     */
    private void stampCoverage(long orgId, LocalDate day, boolean stale) {
        var reviewer = userIds.get(userIds.size() - 1);
        var stamp = stale ? "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 45 DAY)" : "UTC_TIMESTAMP(6)";
        jdbc.update("""
                INSERT INTO cost_history_coverage(org_id,currency,scope_type,scope_id,posting_day,
                  status,basis,review_note,ledger_entry_count,ledger_max_entry_id,
                  reviewed_by_user_id,updated_at)
                VALUES (?, 'CNY', 'ORGANIZATION', ?, ?, 'REVIEWED_COMPLETE', 'MANUAL_LEDGER_REVIEW',
                  'traversal fixture', 0, 0, ?, %s)
                """.formatted(stamp), orgId, orgId, day, reviewer);
    }

    /**
     * Real second and third activity sources for one organization: a SETTLED
     * gateway settlement plus POSTED ledger lineage. FK checks are disabled on the
     * single bound transaction connection so the fixture does not have to
     * fabricate the whole Gateway object graph; the ledger/settlement rows it
     * creates reference each other for real so teardown can delete them cleanly.
     */
    private void seedSettlementAndLedger(long orgId, boolean stale) {
        var stamp = stale ? "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 45 DAY)" : "UTC_TIMESTAMP(6)";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
            try {
                var marker = System.nanoTime();
                jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                                + "billing_period_id,status,posting_actor_type,posted_by_member_id,"
                                + "posted_at,created_at) VALUES (?,?,'GATEWAY_SETTLEMENT',?,1,'POSTED',"
                                + "'SYSTEM',NULL,%s,UTC_TIMESTAMP(6))".formatted(stamp),
                        orgId, "sched-lp-" + marker, marker);
                var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO gateway_settlement(org_id,settlement_key,request_id,"
                                + "route_attempt_id,usage_fact_id,billing_period_id,financial_scope_type,"
                                + "financial_scope_id,provider_account_id,provider_model_id,"
                                + "pricing_version_id,currency,calculated_amount_raw,posted_amount,"
                                + "rounding_delta,status,attempt_count,ledger_posting_id,created_at,"
                                + "settled_at,updated_at) VALUES (?,?,1,1,1,1,'PROJECT',1,1,1,1,'USD',"
                                + "1,1,0,'SETTLED',0,?,UTC_TIMESTAMP(6),%s,%s)"
                                .formatted(stamp, stamp),
                        orgId, "sched-settle-" + marker, postingId);
                var settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,"
                                + "currency,project_id,source_gateway_settlement_id,created_at) "
                                + "VALUES (?,?,0,'COST',1.00000000,'USD',1,?,UTC_TIMESTAMP(6))",
                        orgId, postingId, settlementId);
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        });
    }
}
