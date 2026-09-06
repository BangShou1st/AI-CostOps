package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.reconciliation.application.PeriodCloseService;
import com.aicostops.reconciliation.application.PeriodCloseService.ReopenPeriodCommand;
import com.aicostops.reconciliation.application.ReconciliationRunService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 11 race matrix additions on real MySQL: Provider Charge posting vs
 * Gateway dispatch (the Hybrid fence never allows a false allow), cross-period
 * adjustment vs explicit Reopen, and CLOSED-period run admission vs Reopen.
 * All races are deterministic (row locks and latches, no sleeps) and assert
 * business uniqueness directly against the database.
 */
@SpringBootTest
@Tag("integration")
class M15HybridRaceMatrixIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired ProviderChargePostingService postings;
    @Autowired BillingPeriodFinancialWriteFence periodFence;
    @Autowired ReconciliationRunService runs;
    @Autowired PeriodCloseService closeService;
    @Autowired PlatformTransactionManager transactionManager;

    private final ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
    private AuthenticatedUser actor;
    private AuthenticatedUser reopenActor;
    private long periodId;

    @AfterEach
    void stopExecutor() {
        raceExecutor.shutdownNow();
    }

    @BeforeEach
    void raceSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'LEDGER_POST','LEDGER_CORRECT','RECONCILIATION_RESOLVE',
                  'RECONCILIATION_RUN','PERIOD_READ','PERIOD_REOPEN')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);
        reopenActor = actor;

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
    }

    @Test
    void dispatchFirstBlocksPostingWithoutDisposition() {
        var chargeId = insertPostableCharge("10.00000000");
        insertBillableAttemptOnBaseAccount("BILLABLE_POSSIBLE");

        assertThatThrownBy(() -> postings.post(actor, chargeId, new PostSourceCommand(List.of())))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("HYBRID_RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='PROVIDER_CHARGE'",
                Long.class, orgId)).isZero();
    }

    @Test
    void dispatchFirstYieldsToLegalScopedDirectDisposition() {
        var chargeId = insertPostableCharge("10.00000000");
        insertBillableAttemptOnBaseAccount("BILLABLE_POSSIBLE");
        jdbc.update("""
                INSERT INTO provider_charge_disposition(
                  org_id,charge_fact_id,disposition,decision_source,decided_by_member_id,
                  reason_code,resolution_note,created_at)
                VALUES (?,?,'DIRECT_PROVIDER_CHARGE','MANUAL',?,'MANUAL_DIRECT',
                  'Statement-only direct cost',UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);

        var posted = postings.post(actor, chargeId, new PostSourceCommand(List.of()));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND id=? AND "
                        + "source_type='PROVIDER_CHARGE'",
                Long.class, orgId, posted.posting().id())).isEqualTo(1L);
    }

    @Test
    void postingAndGatewayDispatchRaceNeverCreatesAmbiguousFinancialTruth() throws Exception {
        var chargeId = insertPostableCharge("10.00000000");

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var postingFailure = new AtomicReference<Throwable>();
        var dispatchFailure = new AtomicReference<Throwable>();

        // The dispatch side creates a durable billable attempt inside a
        // transaction that first takes the BillingPeriod fence, exactly like
        // the real Gateway dispatch path, so both sides serialize on the
        // period row.
        var dispatch = new TransactionTemplate(transactionManager);
        var postingFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return postings.post(actor, chargeId, new PostSourceCommand(List.of()));
            } catch (Throwable failure) {
                postingFailure.set(failure);
                return null;
            }
        });
        var dispatchFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                dispatch.execute(status -> {
                    periodFence.lockById(orgId, periodId);
                    insertBillableAttemptOnBaseAccount("BILLABLE_POSSIBLE");
                    return null;
                });
                return null;
            } catch (Throwable failure) {
                dispatchFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        postingFuture.get(60, TimeUnit.SECONDS);
        dispatchFuture.get(60, TimeUnit.SECONDS);

        // Invariants after either ordering:
        // - at most one PROVIDER_CHARGE posting ever exists;
        // - a posting without a DIRECT disposition is only legal when the
        //   durable billable attempt was not visible at decision time (the
        //   period fence serializes the two transactions);
        // - no GATEWAY_SETTLEMENT financial truth may coexist with a fresh
        //   Hybrid-overlap posting decision.
        var postingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='PROVIDER_CHARGE'",
                Long.class, orgId);
        var attemptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_route_attempt WHERE org_id=? AND org_id=?",
                Long.class, orgId, orgId);
        assertThat(attemptCount).isEqualTo(1L);
        assertThat(postingCount).isIn(0L, 1L);
        if (postingCount == 1) {
            assertThat(dispatchFailure.get()).isNull();
            var dispositionCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                    Long.class, orgId);
            var settlementCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gateway_settlement WHERE org_id=?",
                    Long.class, orgId);
            assertThat(dispositionCount).isZero();
            assertThat(settlementCount).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                    .isEqualTo(1L);
        } else {
            assertThat(postingFailure.get()).isInstanceOf(DomainException.class);
            assertThat(((Throwable) postingFailure.get()).getMessage())
                    .contains("HYBRID_RECONCILIATION_REQUIRED");
        }
    }

    @Test
    void crossPeriodAdjustmentAndExplicitReopenConverge() throws Exception {
        // CLOSED historical period with a successful CloseRun so explicit
        // PERIOD_REOPEN is available, plus an OPEN correction period.
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,'CLOSED',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "2026-07-01 00:00:00.000000", AUG_START);
        var closedPeriodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO period_close_run(org_id,billing_period_id,close_generation,
                  attempt_no,status,reconciliation_run_id,started_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?, ?, 1, 1, 'CLOSED', NULL, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, closedPeriodId, actorMemberId);
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, closedPeriodId, "9".repeat(64), actorMemberId);
        var runId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? AND billing_period_id=?",
                Long.class, orgId, closedPeriodId);
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        var caseId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_case WHERE org_id=? AND reconciliation_run_id=?",
                Long.class, orgId, runId);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var reopenFailure = new AtomicReference<Throwable>();

        var adjustmentFailure = new AtomicReference<Throwable>();
        var adjustmentFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return adjustments().postCaseFullAdjustment(actor,
                        new com.aicostops.reconciliation.application.ReconciliationAdjustmentService
                                .CaseFullAdjustmentCommand(caseId,
                                new java.math.BigDecimal("2.00000000"), periodId,
                                List.of(new com.aicostops.reconciliation.application
                                        .ReconciliationAdjustmentService.AdjustmentLine(0,
                                        "PROJECT", projectId,
                                        new java.math.BigDecimal("2.00000000"))),
                                "CROSS_PERIOD_RESOLVED", "Reviewed cross-period correction"),
                        "race-xperiod-adj");
            } catch (Throwable failure) {
                adjustmentFailure.set(failure);
                return null;
            }
        });
        var reopenFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return closeService.reopen(reopenActor, closedPeriodId,
                        new ReopenPeriodCommand("REVIEWED_REOPEN", "Reviewed explicit reopen"));
            } catch (Throwable failure) {
                reopenFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        adjustmentFuture.get(60, TimeUnit.SECONDS);
        reopenFuture.get(60, TimeUnit.SECONDS);

        // Both reviewed actions converge deterministically. Either the
        // adjustment won (it posts into the OPEN correction period while the
        // historical period was still CLOSED and reopen then flips it), or the
        // explicit reopen won first and the historical period became OPEN, in
        // which case a cross-period adjustment is correctly rejected (an OPEN
        // reconciled period receives only its own adjustment).
        var adjustmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM billing_period WHERE id=?", String.class, periodId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM billing_period WHERE id=?", String.class, closedPeriodId))
                .isEqualTo("OPEN");
        if (adjustmentCount == 1) {
            assertThat(adjustmentFailure.get()).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT adjustment_period_id FROM reconciliation_adjustment WHERE org_id=?",
                    Long.class, orgId)).isEqualTo(periodId);
            assertThat(jdbc.queryForObject(
                    "SELECT actual_amount FROM budget WHERE billing_period_id=? AND scope_id=?",
                    java.math.BigDecimal.class, periodId, projectId))
                    .isEqualByComparingTo("2.00000000");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                            + "source_type='RECONCILIATION_ADJUSTMENT'",
                    Long.class, orgId)).isEqualTo(1L);
        } else {
            assertThat(adjustmentCount).isZero();
            assertThat(adjustmentFailure.get()).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                            + "source_type='RECONCILIATION_ADJUSTMENT'",
                    Long.class, orgId)).isZero();
        }
    }

    @Test
    void closedPeriodRunAdmissionVsReopenNeverAutoReopens() throws Exception {
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,'CLOSED',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "2026-07-01 00:00:00.000000", AUG_START);
        var closedPeriodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO period_close_run(org_id,billing_period_id,close_generation,
                  attempt_no,status,reconciliation_run_id,started_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?, ?, 1, 1, 'CLOSED', NULL, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, closedPeriodId, actorMemberId);

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var runFailure = new AtomicReference<Throwable>();
        var reopenFailure = new AtomicReference<Throwable>();

        var runFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return runs.run(actor, closedPeriodId);
            } catch (Throwable failure) {
                runFailure.set(failure);
                return null;
            }
        });
        var reopenFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return closeService.reopen(reopenActor, closedPeriodId,
                        new ReopenPeriodCommand("REVIEWED_REOPEN", "Reviewed explicit reopen"));
            } catch (Throwable failure) {
                reopenFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        runFuture.get(60, TimeUnit.SECONDS);
        reopenFuture.get(60, TimeUnit.SECONDS);

        // Evidence reconciliation never mutates the period: only the explicit
        // reopen command may flip the status, and the run itself stays a
        // read-only evidence run on a stable billing period identity.
        var periodStatus = jdbc.queryForObject(
                "SELECT status FROM billing_period WHERE id=?", String.class, closedPeriodId);
        assertThat(periodStatus).isIn("CLOSED", "OPEN");
        var runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE org_id=? AND billing_period_id=?",
                Long.class, orgId, closedPeriodId);
        if (runCount >= 1) {
            assertThat(runFailure.get()).isNull();
        } else {
            assertThat(runFailure.get()).isNotNull();
        }
        // No financial mutation happened through run admission.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private com.aicostops.reconciliation.application.ReconciliationAdjustmentService
            adjustments() {
        return adjustmentsBean;
    }

    @Autowired
    private com.aicostops.reconciliation.application.ReconciliationAdjustmentService
            adjustmentsBean;

    private long insertPostableCharge(String amount) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,999,'GLM','USAGE',?,'USD',?, '2026-08-02 00:00:00','CLEAN',
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, AUG_START);
        var chargeId = jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?,
                  UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, ?,'USD', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, amount, projectId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        return chargeId;
    }

    /** Billable Gateway attempt on the base fixture provider account/currency/period. */
    private void insertBillableAttemptOnBaseAccount(String attemptStatus) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "race-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "race-model-" + suffix, suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var providerCode = "MIMO-" + suffix.substring(0, 10);
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, suffix, "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "race-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, accountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(41), serviceId, projectId,
                projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixedRequestId(), credentialId, serviceId, projectId, projectId,
                modelId, digest(42), digest(43), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), accountId, providerModelId,
                pricingVersionId, attemptStatus);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
    }

    private static String fixedRequestId() {
        return (UUID.randomUUID().toString().replace("-", "")
                + "0000000000000000000000000000").substring(0, 40);
    }

    private static byte[] digest(int seed) {
        var result = new byte[32];
        for (var i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }
}
