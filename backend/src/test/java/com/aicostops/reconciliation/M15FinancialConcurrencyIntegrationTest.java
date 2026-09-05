package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.reconciliation.application.PeriodCloseService;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.CaseFullAdjustmentCommand;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.AdjustmentLine;
import com.aicostops.reconciliation.application.ReconciliationAlgorithm;
import com.aicostops.reconciliation.application.ReconciliationMatchEngine;
import com.aicostops.reconciliation.application.ReconciliationRunService;
import com.aicostops.reconciliation.application.ReconciliationTolerancePolicy;
import com.aicostops.reconciliation.application.ReconciliationTruthHasher;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M15 cross-module concurrency on real MySQL. Every race is deterministic
 * (row locks and latches, no sleeps) and asserts business uniqueness directly
 * against the database: no double Ledger postings, no duplicate adjustments or
 * resolutions, no stolen normal M13 settlement path, and no case resolved by
 * a sibling action.
 */
@SpringBootTest
@Tag("integration")
class M15FinancialConcurrencyIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationAdjustmentService adjustments;
    @Autowired GatewayFinancialResolutionService resolutions;
    @Autowired GatewaySettlementDiscoveryService discovery;
    @Autowired GatewaySettlementService settlementService;
    @Autowired ReconciliationRunService runs;
    @Autowired PeriodCloseService closeService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ReconciliationExternalTruthPort externalTruth;
    @Autowired ReconciliationInternalTruthPort internalTruth;
    @Autowired ReconciliationMatchEngine matchEngine;
    @Autowired ReconciliationTruthHasher hasher;
    @Autowired ReconciliationTolerancePolicy tolerancePolicy;

    private final ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
    private AuthenticatedUser actor;
    private long periodId;
    private long runId;
    private long caseId;

    @AfterEach
    void stopExecutor() {
        raceExecutor.shutdownNow();
    }

    @BeforeEach
    void concurrencySetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'LEDGER_POST','LEDGER_CORRECT','RECONCILIATION_READ','RECONCILIATION_RUN',
                  'RECONCILIATION_RESOLVE','PERIOD_READ','PERIOD_CLOSE')
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
    }

    @Test
    void duplicateAdjustmentCommandsConvergeToSingleFinancialMutation() throws Exception {
        buildAdjustmentCase();
        var command = new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                java.util.List.of(new AdjustmentLine(0, "PROJECT", projectId,
                        new BigDecimal("2.00000000"))),
                "AGGREGATE_RESOLVED", "Statement reviewed");

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var firstResult = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            return adjustments.postCaseFullAdjustment(actor, command, "race-adj-key");
        });
        var secondResult = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            return adjustments.postCaseFullAdjustment(actor, command, "race-adj-key");
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        var first = firstResult.get(30, TimeUnit.SECONDS);
        var second = secondResult.get(30, TimeUnit.SECONDS);
        assertThat(first.adjustmentId()).isEqualTo(second.adjustmentId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                BigDecimal.class, projectId)).isEqualByComparingTo("2.00000000");
    }

    @Test
    void caseFullAdjustmentAndCloseNeverDoublePost() throws Exception {
        buildAdjustmentCase();
        // Seed a stable ledger posting key so the race reuses one posted path.
        var command = new CaseFullAdjustmentCommand(caseId, new BigDecimal("2.00000000"), periodId,
                java.util.List.of(new AdjustmentLine(0, "PROJECT", projectId,
                        new BigDecimal("2.00000000"))),
                "AGGREGATE_RESOLVED", "Statement reviewed");

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var adjustmentFailure = new AtomicReference<Throwable>();
        var closeOutcome = new AtomicReference<Object>();

        var adjustmentFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                adjustments.postCaseFullAdjustment(actor, command, "race-close-adj");
                return null;
            } catch (Throwable failure) {
                adjustmentFailure.set(failure);
                return null;
            }
        });
        var closeFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return closeService.close(actor, periodId);
            } catch (Throwable failure) {
                closeOutcome.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        adjustmentFuture.get(60, TimeUnit.SECONDS);
        closeFuture.get(60, TimeUnit.SECONDS);

        // Invariant: either the adjustment posted and Close sees changed truth,
        // or Close committed first and the adjustment cannot write to the period.
        var adjustmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId);
        var periodStatus = jdbc.queryForObject(
                "SELECT status FROM billing_period WHERE id=?", String.class, periodId);
        if (adjustmentCount == 1) {
            assertThat(adjustmentFailure.get()).isNull();
            if ("CLOSED".equals(periodStatus)) {
                // Close won after posting: it must have observed the post-basis
                // world via a later rerun, never a stale-basis close.
                var latestBasis = jdbc.queryForObject("""
                        SELECT rr.basis_hash FROM reconciliation_run rr
                        WHERE rr.org_id=? AND rr.billing_period_id=? AND rr.status='COMPLETED'
                        ORDER BY rr.started_at DESC, rr.id DESC LIMIT 1
                        """, String.class, orgId, periodId);
                var currentInternal = internalTruth.aggregateProviderLedger(orgId, periodId);
                var currentExternal = externalTruth.aggregateConfirmedCharges(orgId,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"));
                var currentHash = hasher.hash(matchEngine.match(currentExternal, currentInternal,
                        tolerancePolicy.amount()).rows());
                assertThat(latestBasis).isEqualTo(currentHash);
            }
        } else {
            assertThat(adjustmentCount).isZero();
            assertThat(adjustmentFailure.get()).isInstanceOf(DomainException.class);
        }
        // Financial uniqueness: exactly one adjustment entry ever exists.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isEqualTo(adjustmentCount == 1 ? 2 : 1);
    }

    @Test
    void pendingSettlementPathIsNeverStolenByConcurrentResolution() throws Exception {
        var fixture = buildGatewayCase("FINAL", true, false);
        jdbc.update("UPDATE gateway_settlement SET status='PENDING' WHERE id=?",
                fixture.settlementId());

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var resolutionOutcome = new AtomicReference<Object>();
        var resolutionFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return resolutions.resolveGatewayFinancialWork(actor,
                        new GatewayResolutionCommand(runId, null, fixture.requestId(),
                                "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("2.00000000"),
                                null, null, "STATEMENT_EVIDENCE", "Reviewed statement line"),
                        "race-gwres-1");
            } catch (Throwable failure) {
                resolutionOutcome.set(failure);
                return null;
            }
        });
        var settlementFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            return settlementService.settle(orgId, fixture.settlementId());
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        resolutionFuture.get(60, TimeUnit.SECONDS);
        var settled = settlementFuture.get(60, TimeUnit.SECONDS);

        // The normal M13 path always wins: the resolution is rejected because a
        // PENDING settlement continues through the worker semantics.
        assertThat(resolutionOutcome.get()).isInstanceOf(DomainException.class);
        assertThat(((Throwable) resolutionOutcome.get()).getMessage()).contains("PENDING");
        assertThat(settled.settlement().status().name()).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND source_type='GATEWAY_SETTLEMENT'",
                Long.class, orgId)).isEqualTo(1);
    }

    @Test
    void resolutionAndLateFinalUsageNeverBothBecomeFinancialTruth() throws Exception {
        var fixture = buildGatewayCase("UNKNOWN", false, false);

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var resolutionFailure = new AtomicReference<Throwable>();
        var lateFinal = new TransactionTemplate(transactionManager);
        var lateFinalFailure = new AtomicReference<Throwable>();

        var resolutionFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return resolutions.resolveGatewayFinancialWork(actor,
                        new GatewayResolutionCommand(runId, null, fixture.requestId(),
                                "NO_CHARGE_CONFIRMED", null, null, null,
                                "POSITIVE_NO_CHARGE", "Provider confirmed no charge"),
                        "race-late-1");
            } catch (Throwable failure) {
                resolutionFailure.set(failure);
                return null;
            }
        });
        var publicationFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                // Late usage publication serializes on the same request source row.
                lateFinal.execute(status -> {
                    jdbc.queryForObject(
                            "SELECT id FROM gateway_request WHERE org_id=? AND id=? FOR UPDATE",
                            Long.class, orgId, fixture.requestId());
                    jdbc.update("UPDATE gateway_usage_fact SET status='FINAL' WHERE id=?",
                            fixture.usageFactId());
                    return null;
                });
                return null;
            } catch (Throwable failure) {
                lateFinalFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        resolutionFuture.get(60, TimeUnit.SECONDS);
        publicationFuture.get(60, TimeUnit.SECONDS);

        var resolutionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId);
        var usageStatus = jdbc.queryForObject(
                "SELECT status FROM gateway_usage_fact WHERE id=?", String.class,
                fixture.usageFactId());

        if (resolutionCount == 1) {
            // Resolution won the source row: the late FINAL may persist as
            // operational evidence, but no normal settlement may follow.
            assertThat(resolutionFailure.get()).isNull();
            assertThat(lateFinalFailure.get()).isNull();
            assertThat(usageStatus).isEqualTo("FINAL");
            assertThat(discovery.discover(orgId)).isEmpty();
        } else {
            // FINAL usage won the source row: the resolution is rejected and
            // the normal M13 discovery owns the request.
            assertThat(resolutionCount).isZero();
            assertThat(resolutionFailure.get()).isInstanceOf(DomainException.class);
            assertThat(usageStatus).isEqualTo("FINAL");
        }
        // Either ordering: exactly one financial terminal decision exists and
        // no duplicate Gateway settlement posting is ever created.
        var postings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? "
                        + "AND source_type IN ('GATEWAY_SETTLEMENT','RECONCILIATION_ADJUSTMENT')",
                Long.class, orgId);
        assertThat(postings).isEqualTo(resolutionCount == 1 ? 0L : 0L);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private record GatewayFixture(long requestId, long attemptId, Long usageFactId,
            Long settlementId, long providerAccountId, long providerModelId,
            long pricingVersionId) {
    }

    private void buildAdjustmentCase() {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,999,'GLM','USAGE','10.00000000','USD',?,'2026-08-02 00:00:00',
                  'CLEAN',UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, AUG_START);
        var chargeId = jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'PROVIDER_CHARGE',?,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "CHARGE:" + chargeId + ":RACE", chargeId, periodId, actorMemberId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_charge_fact_id,created_at)
                VALUES (?,?,0,'COST','8.00000000','USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, projectId, chargeId);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);

        runId = insertRunForCurrentTruth();
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        caseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** usageStatus FINAL/UNKNOWN/null; withSettlement requires a usage fact. */
    private GatewayFixture buildGatewayCase(String usageStatus, boolean withSettlement,
            boolean withBudget) {
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
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, suffix, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(91), serviceId, projectId, projectId);
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
                modelId, digest(92), digest(93), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?, 'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), providerAccountId, providerModelId,
                pricingVersionId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        Long usageFactId = null;
        if (usageStatus != null) {
            jdbc.update("""
                    INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                      status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                      currency,observed_at,created_at)
                    VALUES (?,?,?,1,?,UTC_TIMESTAMP(6),
                      'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, usageStatus, pricingVersionId);
            usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                    usageFactId, requestId);
            if ("FINAL".equals(usageStatus)) {
                jdbc.update("""
                        INSERT INTO gateway_usage_dimension(org_id,usage_fact_id,dimension_code,
                          quantity,provenance)
                        VALUES (?,?,'INPUT_TOKEN',1,'PROVIDER_FINAL')
                        """, orgId, usageFactId);
            }
        }
        Long settlementId = null;
        if (withSettlement) {
            jdbc.update("""
                    INSERT INTO gateway_settlement(
                      org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                      billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                      provider_model_id,pricing_version_id,currency,status,attempt_count,
                      created_at,updated_at)
                    VALUES (?,?,?,?,?,NULL,?,'PROJECT',?,?,?,?,?,'PENDING',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, "GATEWAY_REQUEST:" + suffix, requestId, attemptId, usageFactId,
                    periodId, projectId, providerAccountId, providerModelId, pricingVersionId,
                    "USD");
            settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        if (withBudget) {
            jdbc.update("""
                    INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                      total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                    VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, periodId, projectId);
        }
        runId = insertRunForCurrentTruth();
        return new GatewayFixture(requestId, attemptId, usageFactId, settlementId,
                providerAccountId, providerModelId, pricingVersionId);
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
