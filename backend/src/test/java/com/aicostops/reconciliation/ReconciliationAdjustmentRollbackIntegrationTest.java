package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentFailureInjector;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.CaseFullAdjustmentCommand;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.AdjustmentLine;
import com.aicostops.reconciliation.application.ReconciliationAlgorithm;
import com.aicostops.reconciliation.application.ReconciliationMatchEngine;
import com.aicostops.reconciliation.application.ReconciliationTolerancePolicy;
import com.aicostops.reconciliation.application.ReconciliationTruthHasher;
import com.aicostops.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Injected failure after any CASE_FULL financial mutation boundary rolls the
 * whole transaction back: no partial adjustment, Ledger, Budget or case state
 * may survive.
 */
@SpringBootTest
@Tag("integration")
class ReconciliationAdjustmentRollbackIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationAdjustmentService adjustments;
    @Autowired ReconciliationExternalTruthPort externalTruth;
    @Autowired ReconciliationInternalTruthPort internalTruth;
    @Autowired ReconciliationMatchEngine matchEngine;
    @Autowired ReconciliationTruthHasher hasher;
    @Autowired ReconciliationTolerancePolicy tolerancePolicy;
    @MockitoBean ReconciliationAdjustmentFailureInjector failureInjector;

    private AuthenticatedUser actor;
    private long periodId;
    private long chargeId;
    private long caseId;
    private long runId;

    @BeforeEach
    void rollbackSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_RESOLVE','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        chargeId = insertAdjustmentCharge("10.00000000");
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
    void injectedFailureAfterEachFinancialBoundaryRollsBackCompletely() {
        for (var stage : List.of("ADJUSTMENT_INSERTED", "LEDGER_ENTRY_INSERTED",
                "BUDGET_ACTUAL_MUTATED", "AUDIT_WRITTEN", "CASE_RESOLVED")) {
            var invocations = new AtomicInteger();
            doAnswer(invocation -> {
                if (stage.equals(invocation.getArgument(0)) && invocations.incrementAndGet() > 0) {
                    throw new IllegalStateException("Injected failure at " + stage);
                }
                return null;
            }).when(failureInjector).after(org.mockito.ArgumentMatchers.anyString());

            assertThatThrownBy(() -> adjustments.postCaseFullAdjustment(actor,
                    new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                            List.of(new AdjustmentLine(0, "PROJECT", projectId,
                                    new BigDecimal("2.00000000"))),
                            "R", "N"), "rollback-" + stage))
                    .hasMessageContaining("Injected failure");

            assertNoPartialAdjustmentState();
        }
    }

    private void assertNoPartialAdjustmentState() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                BigDecimal.class, projectId)).isEqualByComparingTo("0.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reconciliation_case WHERE id=?", String.class, caseId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_idempotency WHERE org_id=? AND operation='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isZero();
    }

    private long insertAdjustmentCharge(String amount) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,999,'GLM','USAGE',?,'USD',?,'2026-08-02 00:00:00','CLEAN',
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, AUG_START);
        return jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);
    }

    private void insertChargeLedgerEntry(long charge, String amount) {
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'PROVIDER_CHARGE',?,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "CHARGE:" + charge + ":RB", charge, periodId, actorMemberId);
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
}
