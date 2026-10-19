package com.aicostops.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.aicostops.intelligence.application.CostAnalysisInputService;
import com.aicostops.intelligence.application.CostIntelligenceAnalysisService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Scheduler discoverability for failures BEFORE input-identity capture: such
 * a failure has no cost_intelligence_run row (identity acquisition is what
 * creates it), so the WARN log emitted by runDueAnalyses is the only trace.
 * The identity acquisition is sabotaged for the FIRST active organization;
 * the sweep must log a WARN naming organization/currency/date and still reach
 * the second organization, which completes its run normally.
 *
 * <p>Organization-level isolation: the per-organization settledCurrencies
 * lookup runs OUTSIDE the per-run catch, so before the fix a failing currency
 * query for one organization aborted the whole round (every later organization
 * and page was silently lost). The isolation test sabotages the lookup for the
 * FIRST active organization only; the sweep must log a lookup WARN for that
 * organization and still complete the remaining organization's run.
 */
@SpringBootTest
@Tag("integration")
class CostIntelligenceSchedulerFailureVisibilityIntegrationTest
        extends AuthenticationContainersSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired CostIntelligenceAnalysisService analysis;
    @MockitoSpyBean CostAnalysisInputService inputs;
    @MockitoSpyBean com.aicostops.intelligence.infrastructure.CostFactsMapper facts;

    private final List<Long> activeOrgs = new java.util.ArrayList<>();
    private long failingOrg;
    private long succeedingOrg;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logWatcher;
    private ch.qos.logback.classic.Logger serviceLogger;

    @BeforeEach
    void seedTwoActiveOrganizations() {
        // Clean the coverage tables so the scheduler sweep sees exactly the two
        // organizations this test seeds (recentlyActiveOrgs has no filter that
        // would exclude rows left by other tests in the shared container).
        jdbc.update("DELETE FROM cost_intelligence_run");
        jdbc.update("DELETE FROM cost_history_coverage");
        jdbc.update("DELETE FROM cost_history_coverage_revision");

        failingOrg = insertActiveOrg("failing");
        succeedingOrg = insertActiveOrg("succeeding");
        activeOrgs.add(failingOrg);
        activeOrgs.add(succeedingOrg);

        serviceLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CostIntelligenceAnalysisService.class);
        logWatcher = new ch.qos.logback.core.read.ListAppender<>();
        logWatcher.start();
        serviceLogger.addAppender(logWatcher);
        serviceLogger.setLevel(ch.qos.logback.classic.Level.WARN);

        // Sabotage identity capture BEFORE any run row exists for the first
        // organization only.
        doThrow(new IllegalStateException("identity capture unavailable"))
                .when(inputs).lockAndCapture(org.mockito.ArgumentMatchers.eq(failingOrg),
                        anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @AfterEach
    void cleanup() {
        serviceLogger.detachAppender(logWatcher);
        org.mockito.Mockito.reset(inputs, facts);
        jdbc.update("DELETE FROM cost_intelligence_run");
        for (var orgId : activeOrgs) {
            jdbc.update("DELETE FROM cost_history_coverage WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", orgId);
            jdbc.update("DELETE FROM organization WHERE id=?", orgId);
        }
        jdbc.update("DELETE FROM app_user WHERE display_name='Traversal Fixture'");
    }

    /**
     * The scheduled sweep must not stop at the identity-less failure, the
     * failure must leave a WARN trace naming the organization, and the second
     * organization must still complete its run.
     */
    @Test
    void identityCaptureFailureLeavesWarnTraceAndSweepContinues() {
        analysis.runDueAnalyses();

        var warns = logWatcher.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(
                        "Cost Intelligence scheduled analysis failed"))
                .toList();
        assertThat(warns).as("the identity-capture failure must be logged as WARN")
                .hasSize(1);
        assertThat(warns.getFirst().getFormattedMessage())
                .contains("organization " + failingOrg)
                .contains("CNY");

        // The failure happened before identity capture: no run row exists for
        // the failing organization.
        var failingRunRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_intelligence_run WHERE org_id=?",
                Integer.class, failingOrg);
        assertThat(failingRunRows).isZero();

        // The sweep continued: the second organization produced a COMPLETED run.
        var succeedingRunStatus = jdbc.queryForObject(
                "SELECT status FROM cost_intelligence_run WHERE org_id=?",
                String.class, succeedingOrg);
        assertThat(succeedingRunStatus).isEqualTo("COMPLETED");
    }

    /**
     * The WARN carries the failing organization identity, but its failure text
     * must pass the shared fail-closed secret redactor: a synthetic
     * credential-shaped marker in the exception message is never written raw.
     */
    @Test
    void identityCaptureFailureWarnRedactsSecretShapedFailureText() {
        doThrow(new IllegalStateException(
                "provider credential api_key=sk-synthetic-marker-0000000000 rejected"))
                .when(inputs).lockAndCapture(org.mockito.ArgumentMatchers.eq(failingOrg),
                        anyString(), org.mockito.ArgumentMatchers.any(), anyInt());

        analysis.runDueAnalyses();

        var warns = logWatcher.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(
                        "Cost Intelligence scheduled analysis failed"))
                .toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.getFirst().getFormattedMessage())
                .as("the scheduler WARN must not write credential-shaped text")
                .doesNotContain("sk-synthetic-marker-0000000000")
                .doesNotContain("api_key=sk")
                .contains("[REDACTED]")
                .contains("organization " + failingOrg);
    }

    /**
     * The per-organization settledCurrencies lookup is NOT covered by the
     * per-run catch: before the fix a failing currency query for one organization
     * aborted the whole round, so every later organization and page was skipped.
     * The lookup is sabotaged for the FIRST active organization only; the sweep
     * must log a lookup WARN for that organization and still complete the second
     * organization's run normally.
     */
    @Test
    void settledCurrenciesLookupFailureIsConfinedToItsOrganization() {
        org.mockito.Mockito.reset(facts);
        doThrow(new IllegalStateException("currency lookup storage unavailable"))
                .when(facts).settledCurrencies(failingOrg);

        analysis.runDueAnalyses();

        var lookupWarns = logWatcher.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(
                        "Cost Intelligence scheduled currency lookup failed"))
                .toList();
        assertThat(lookupWarns).as("the lookup failure must be logged as WARN").hasSize(1);
        assertThat(lookupWarns.getFirst().getFormattedMessage())
                .contains("organization " + failingOrg);

        // The failing organization got no analysis (its currencies were never
        // resolved), but the sweep continued: the second organization completed.
        var failingRunRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_intelligence_run WHERE org_id=?",
                Integer.class, failingOrg);
        assertThat(failingRunRows).isZero();
        var succeedingRunStatus = jdbc.queryForObject(
                "SELECT status FROM cost_intelligence_run WHERE org_id=?",
                String.class, succeedingOrg);
        assertThat(succeedingRunStatus).isEqualTo("COMPLETED");
    }

    private long insertActiveOrg(String label) {
        var slug = "sched-visibility-" + label + "-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at) "
                + "VALUES('Scheduler visibility test',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                slug);
        var orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,
                  created_at,updated_at)
                VALUES (?, 'Traversal Fixture','ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "visibility-" + slug + "@example.test");
        var reviewer = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, "visibility-" + slug + "@example.test");
        jdbc.update("""
                INSERT INTO cost_history_coverage(org_id,currency,scope_type,scope_id,posting_day,
                  status,basis,review_note,ledger_entry_count,ledger_max_entry_id,
                  reviewed_by_user_id,updated_at)
                VALUES (?, 'CNY', 'ORGANIZATION', ?, ?, 'REVIEWED_COMPLETE', 'MANUAL_LEDGER_REVIEW',
                  'visibility fixture', 0, 0, ?, UTC_TIMESTAMP(6))
                """, orgId, orgId, LocalDate.now(java.time.Clock.systemUTC()).minusDays(1), reviewer);
        return orgId;
    }
}
