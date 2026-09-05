package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.CaseFullAdjustmentCommand;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.AdjustmentLine;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.CaseFullAdjustmentResult;
import com.aicostops.reconciliation.application.ReconciliationAlgorithm;
import com.aicostops.reconciliation.application.ReconciliationMatchEngine;
import com.aicostops.reconciliation.application.ReconciliationTolerancePolicy;
import com.aicostops.reconciliation.application.ReconciliationTruthHasher;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CASE_FULL reconciliation adjustment: the posted amount is exactly the
 * current external-internal difference against a current run basis, with
 * explicit same-org allocation lines, append-only Ledger truth and exact
 * Budget Actual mutation.
 */
@SpringBootTest
@Tag("integration")
class ReconciliationAdjustmentIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationAdjustmentService adjustments;
    @Autowired ReconciliationExternalTruthPort externalTruth;
    @Autowired ReconciliationInternalTruthPort internalTruth;
    @Autowired ReconciliationMatchEngine matchEngine;
    @Autowired ReconciliationTruthHasher hasher;
    @Autowired ReconciliationTolerancePolicy tolerancePolicy;

    private AuthenticatedUser actor;
    private long periodId;
    private long otherOpenPeriodId;
    private long closingPeriodId;
    private long chargeId;
    private long caseId;
    private long runId;

    @BeforeEach
    void adjustmentSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_RESOLVE','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);

        periodId = insertPeriod("OPEN", AUG_START, SEP_START);
        otherOpenPeriodId = insertPeriod("OPEN", "2026-09-01 00:00:00", "2026-10-01 00:00:00");
        closingPeriodId = insertPeriod("CLOSING", "2026-10-01 00:00:00", "2026-11-01 00:00:00");
        chargeId = insertCharge("10.00000000", "CLEAN");
        // Internal truth: a Provider Charge Ledger entry of 8 against the
        // external statement charge of 10 -> required adjustment = +2.
        insertChargeLedgerEntry(chargeId, "8.00000000");
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);

        runId = insertRunForCurrentTruth();
        caseId = insertCase();
    }

    @Test
    void postsExactCaseFullAdjustmentWithExplicitLineAndBudgetActual() {
        var result = adjustments.postCaseFullAdjustment(actor, new CaseFullAdjustmentCommand(
                caseId, new BigDecimal("2.00000000"), periodId,
                List.of(new AdjustmentLine(0, "PROJECT", projectId, new BigDecimal("2.00000000"))),
                "AGGREGATE_RESOLVED", "Statement reviewed"));

        var adjustment = jdbc.queryForMap(
                "SELECT * FROM reconciliation_adjustment WHERE id=?", result.adjustmentId());
        assertThat(adjustment.get("adjustment_scope")).isEqualTo("CASE_FULL");
        assertThat(((BigDecimal) adjustment.get("amount"))).isEqualByComparingTo("2.00000000");
        assertThat(adjustment.get("reconciliation_case_id")).isEqualTo(caseId);
        assertThat(adjustment.get("adjustment_period_id")).isEqualTo(periodId);

        var posting = jdbc.queryForMap("""
                SELECT posting_key,source_type,billing_period_id,posting_actor_type
                FROM ledger_posting WHERE posting_key=?
                """, "RECONCILIATION_ADJUSTMENT:" + result.adjustmentId());
        assertThat(posting.get("source_type")).isEqualTo("RECONCILIATION_ADJUSTMENT");
        assertThat(posting.get("posting_actor_type")).isEqualTo("MEMBER");

        var entry = jdbc.queryForMap("""
                SELECT entry_type,amount,currency,project_id FROM ledger_entry
                WHERE posting_id=?
                """, jdbc.queryForObject("""
                SELECT id FROM ledger_posting WHERE posting_key=?
                """, Long.class, "RECONCILIATION_ADJUSTMENT:" + result.adjustmentId()));
        assertThat(entry.get("entry_type")).isEqualTo("ADJUSTMENT");
        assertThat(((BigDecimal) entry.get("amount"))).isEqualByComparingTo("2.00000000");
        assertThat(entry.get("currency")).isEqualTo("USD");

        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                BigDecimal.class, projectId)).isEqualByComparingTo("2.00000000");

        var resolved = jdbc.queryForMap(
                "SELECT status,reason_code FROM reconciliation_case WHERE id=?", caseId);
        assertThat(resolved.get("status")).isEqualTo("RESOLVED");
        assertThat(resolved.get("reason_code")).isEqualTo("RECONCILIATION_ADJUSTMENT_POSTED");
    }

    @Test
    void rejectsAmountThatIsNotCurrentExternalMinusInternal() {
        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("3.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("3.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("amount");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void rejectsZeroRequiredAdjustmentAsFinancialAction() {
        // Make truth match: internal 10 == external 10 -> required zero.
        insertChargeLedgerEntry(chargeId, "2.00000000");
        rerunAndReplaceCase();

        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("0.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("0.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsStaleRunBasisBeforeAnyFinancialMutation() {
        // Financial truth changes after the run: the old basis is stale.
        insertChargeLedgerEntry(chargeId, "1.00000000");

        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("2.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("STALE_BASIS");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isZero();
    }

    @Test
    void rejectsLineSumThatDoesNotEqualAmount() {
        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("1.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsForeignAllocationTarget() {
        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", foreignProjectId(),
                                new BigDecimal("2.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void openCasePeriodOnlyAcceptsSamePeriodPosting() {
        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"),
                        otherOpenPeriodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("2.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("period");
    }

    @Test
    void closedCasePeriodAcceptsOnlyExplicitOpenCorrectionPeriod() {
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);

        // Same-period write into a CLOSED period is rejected.
        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("2.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class);

        var result = adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"),
                        otherOpenPeriodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("2.00000000"))),
                        "R", "N"));
        assertThat(jdbc.queryForObject(
                "SELECT adjustment_period_id FROM reconciliation_adjustment WHERE id=?",
                Long.class, result.adjustmentId())).isEqualTo(otherOpenPeriodId);
        // The historical CLOSED period was never reopened.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM billing_period WHERE id=?", String.class, periodId))
                .isEqualTo("CLOSED");
    }

    @Test
    void closingCorrectionPeriodIsRejected() {
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);

        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"),
                        closingPeriodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("2.00000000"))),
                        "R", "N")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unbudgetedAdjustmentStillPostsLedgerTruth() {
        jdbc.update("DELETE FROM budget WHERE scope_type='PROJECT' AND scope_id=?", projectId);

        var result = adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                new BigDecimal("2.00000000"))),
                        "R", "N"));

        assertThat(result.adjustmentId()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE source_reconciliation_adjustment_id=?",
                Long.class, result.adjustmentId())).isEqualTo(1);
    }

    @Test
    void sameKeyReplaysOnceAndDifferentBodyConflicts() {
        var command = new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"),
                periodId,
                List.of(new AdjustmentLine(0, "PROJECT", projectId, new BigDecimal("2.00000000"))),
                "R", "N");
        var first = adjustments.postCaseFullAdjustment(actor, command, "adj-key-1");

        var replay = adjustments.postCaseFullAdjustment(actor, command, "adj-key-1");
        assertThat(replay.adjustmentId()).isEqualTo(first.adjustmentId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isEqualTo(1);

        assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                        List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                        new BigDecimal("1.00000000")),
                                new AdjustmentLine(1, "PROJECT", projectId,
                                        new BigDecimal("1.00000000"))),
                        "R", "N"), "adj-key-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    void caseFullAdjustmentNeverConsumesCommitments() {
        adjustments.postCaseFullAdjustment(actor, new CaseFullAdjustmentCommand(
                caseId, new BigDecimal("2.00000000"), periodId,
                List.of(new AdjustmentLine(0, "PROJECT", projectId, new BigDecimal("2.00000000"))),
                "R", "N"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long insertPeriod(String status, String start, String end) {
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, start, end, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?",
                Long.class, orgId);
    }

    private long insertCharge(String amount, String reviewStatus) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,999,'GLM','USAGE',?,'USD',?,'2026-08-02 00:00:00',?,
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, AUG_START, reviewStatus);
        return jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);
    }

    private void insertChargeLedgerEntry(long charge, String amount) {
        var key = "CHARGE:" + charge + ":TEST:" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'PROVIDER_CHARGE',?,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, key, charge, periodId, actorMemberId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_charge_fact_id,created_at)
                VALUES (?,?,0,'COST',?,'USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, amount, projectId, charge);
    }

    private long insertRunForCurrentTruth() {
        var external = externalTruth.aggregateConfirmedCharges(orgId,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
        var internal = internalTruth.aggregateProviderLedger(orgId, periodId);
        var summary = matchEngine.match(external, internal, tolerancePolicy.amount());
        var basisHash = hasher.hash(summary.rows());
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED',?,?,?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, ReconciliationAlgorithm.VERSION,
                tolerancePolicy.amount(), basisHash, actorMemberId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertCase() {
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void rerunAndReplaceCase() {
        jdbc.update("UPDATE reconciliation_case SET status='OPEN' WHERE id=?", caseId);
        // Rebuild the run basis against the new truth and re-point the case.
        jdbc.update("DELETE FROM reconciliation_case WHERE id=?", caseId);
        runId = insertRunForCurrentTruth();
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','10.00000000','0.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        caseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long foreignProjectId() {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, foreignOrgId, "foreign-adj", "foreign-adj");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
