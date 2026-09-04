package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementFailureInjector;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.blockers.GatewayFinancialWorkBlockerProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** End-to-end Gateway financial mutation on real MySQL. */
@SpringBootTest
@Tag("integration")
class GatewaySettlementTransactionIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired GatewaySettlementDiscoveryService discovery;
    @Autowired GatewaySettlementService service;
    @Autowired BillingPeriodMapper periods;
    @Autowired GatewayFinancialWorkBlockerProvider closeBlocker;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean GatewaySettlementFailureInjector failureInjector;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void postsFullActualAndAtomicallyFinalizesBoundReservation() {
        var fixture = fixture(true);
        var discovered = discovery.discover(fixture.orgId());

        var result = service.settle(fixture.orgId(), discovered.getFirst().id());

        assertThat(result.settlement().isSettled()).isTrue();
        assertThat(result.settlement().calculatedAmountRaw()).isEqualByComparingTo("1.80");
        assertThat(result.settlement().postedAmount()).isEqualByComparingTo("1.80000000");
        assertThat(result.settlement().roundingDelta()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_posting
                WHERE org_id=? AND source_type='GATEWAY_SETTLEMENT'
                  AND source_id=? AND posting_actor_type='SYSTEM'
                  AND posted_by_member_id IS NULL
                """, Integer.class, fixture.orgId(), discovered.getFirst().id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE org_id=? AND source_gateway_settlement_id=?
                  AND source_charge_fact_id IS NULL AND source_expense_claim_id IS NULL
                  AND project_id=? AND budget_id=? AND amount='1.80000000'
                """, Integer.class, fixture.orgId(), discovered.getFirst().id(),
                fixture.projectId(), fixture.budgetId())).isOne();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
        assertThat(jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, fixture.reservationId())).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE org_id=? AND event_type='GATEWAY_SETTLEMENT_POSTED'
                  AND subject_id=? AND actor_user_id IS NULL
                """, Integer.class, fixture.orgId(), discovered.getFirst().id())).isOne();
    }

    @Test
    void optionalUnbudgetedUsageStillPostsWithoutBudgetOrReservation() {
        var fixture = fixture(false);
        var discovered = discovery.discover(fixture.orgId());

        var result = service.settle(fixture.orgId(), discovered.getFirst().id());

        assertThat(result.settlement().status().name()).isEqualTo("SETTLED");
        assertThat(result.settlement().reservationId()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE org_id=? AND source_gateway_settlement_id=? AND budget_id IS NULL
                """, Integer.class, fixture.orgId(), discovered.getFirst().id())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
    }

    @Test
    void consumesOnlyTheExplicitlyBoundCommitmentWithoutCappingActual() {
        var fixture = fixture(true, true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();

        service.settle(fixture.orgId(), settlement.id());

        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM budget_commitment WHERE id=?",
                BigDecimal.class, fixture.commitmentId())).isEqualByComparingTo("0.00000000");
        assertThat(jdbc.queryForObject("SELECT status FROM budget_commitment WHERE id=?",
                String.class, fixture.commitmentId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_commitment_usage WHERE budget_commitment_id=?",
                Integer.class, fixture.commitmentId())).isOne();
    }

    @Test
    void repeatedSettlementAfterCommittedResultDoesNotDoublePost() {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();

        service.settle(fixture.orgId(), settlement.id());
        service.settle(fixture.orgId(), settlement.id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_settlement WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
    }

    @Test
    void concurrentWorkersConvergeOnOneSettlementAndOneFinancialMutation() throws Exception {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.settle(fixture.orgId(), settlement.id()));
            var second = executor.submit(() -> service.settle(fixture.orgId(), settlement.id()));

            assertThat(first.get(20, TimeUnit.SECONDS).settlement().status().name())
                    .isEqualTo("SETTLED");
            assertThat(second.get(20, TimeUnit.SECONDS).settlement().status().name())
                    .isEqualTo("SETTLED");
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_settlement WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?",
                Integer.class, fixture.orgId())).isOne();
    }

    @Test
    void releasedBoundReservationStopsAutomaticSettlement() {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();
        jdbc.update("UPDATE budget_reservation SET status='RELEASED',released_at=UTC_TIMESTAMP(6) WHERE id=?",
                fixture.reservationId());

        var result = service.settle(fixture.orgId(), settlement.id());

        assertThat(result.settlement().status().name()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("0.00000000");
    }

    @Test
    void failureAfterLedgerInsertRollsBackEveryFinancialMutation() {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();
        doThrow(new IllegalStateException("after ledger"))
                .when(failureInjector).after("LEDGER_INSERTED");

        assertThatThrownBy(() -> service.settle(fixture.orgId(), settlement.id()))
                .isInstanceOf(IllegalStateException.class).hasMessage("after ledger");
        assertNoFinancialMutation(fixture, settlement.id());
    }

    @Test
    void failureAfterActualMutationRollsBackActualAndEarlierLedgerWrites() {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();
        doThrow(new IllegalStateException("after actual"))
                .when(failureInjector).after("BUDGET_ACTUAL_MUTATED");

        assertThatThrownBy(() -> service.settle(fixture.orgId(), settlement.id()))
                .isInstanceOf(IllegalStateException.class).hasMessage("after actual");
        assertNoFinancialMutation(fixture, settlement.id());
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("0.00000000");
    }

    @Test
    void failureAfterAuditWriteRollsBackAuditAndReservationFinalization() {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();
        doThrow(new IllegalStateException("after audit"))
                .when(failureInjector).after("AUDIT_WRITTEN");

        assertThatThrownBy(() -> service.settle(fixture.orgId(), settlement.id()))
                .isInstanceOf(IllegalStateException.class).hasMessage("after audit");
        assertNoFinancialMutation(fixture, settlement.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, fixture.reservationId())).isEqualTo("ACTIVE");
    }

    @Test
    void settlementGetsPeriodFirstAndCloseWaitsForCommittedFinancialTruth() throws Exception {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();
        var periodLocked = new CountDownLatch(1);
        var releaseSettlement = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("BILLING_PERIOD_LOCKED".equals(invocation.getArgument(0))) {
                periodLocked.countDown();
                assertThat(releaseSettlement.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(failureInjector).after(org.mockito.ArgumentMatchers.anyString());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<GatewaySettlementService.SettlementResult> settlementFuture = executor.submit(
                    () -> service.settle(fixture.orgId(), settlement.id()));
            assertThat(periodLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var closeFuture = submitClose(executor, fixture, new CountDownLatch(0), null, true);
            assertThat(closeFuture.isDone()).isFalse();

            releaseSettlement.countDown();
            assertThat(settlementFuture.get(20, TimeUnit.SECONDS).settlement().status().name())
                    .isEqualTo("SETTLED");
            closeFuture.get(20, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM billing_period WHERE id=?",
                String.class, fixture.periodId())).isEqualTo("CLOSED");
        assertThat(closeBlocker.evaluate(new CloseBlockerContext(fixture.orgId(), fixture.periodId(),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")))
                .passed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
    }

    @Test
    void closeGetsPeriodFirstAndSettlementReconcilesWithoutFinancialMutation() throws Exception {
        var fixture = fixture(true);
        var settlement = discovery.discover(fixture.orgId()).getFirst();
        var closeLocked = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var closeFuture = submitClose(executor, fixture, closeLocked, releaseClose, true);
            assertThat(closeLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<GatewaySettlementService.SettlementResult> settlementFuture = executor.submit(
                    () -> service.settle(fixture.orgId(), settlement.id()));
            Thread.sleep(250);
            assertThat(settlementFuture.isDone()).isFalse();

            releaseClose.countDown();
            closeFuture.get(20, TimeUnit.SECONDS);
            assertThat(settlementFuture.get(20, TimeUnit.SECONDS).settlement().status().name())
                    .isEqualTo("RECONCILIATION_REQUIRED");
        }

        assertThat(jdbc.queryForObject("SELECT status FROM billing_period WHERE id=?",
                String.class, fixture.periodId())).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("0.00000000");
    }

    private Future<?> submitClose(ExecutorService executor, Fixture fixture,
            CountDownLatch locked, CountDownLatch release, boolean complete) {
        return executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    var period = periods.selectByIdForUpdate(fixture.orgId(), fixture.periodId());
                    if (period == null) {
                        throw new IllegalStateException("Period must be present");
                    }
                    locked.countDown();
                    if (release != null) {
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Close test release timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                    if (complete) {
                        var now = Instant.parse("2026-09-04T00:00:00Z");
                        if (periods.markClosing(fixture.orgId(), period.id(), period.version(), now) != 1
                                || periods.markClosed(fixture.orgId(), period.id(), period.version() + 1, now) != 1) {
                            throw new IllegalStateException("Close test must advance the period");
                        }
                    }
                }));
    }

    private void assertNoFinancialMutation(Fixture fixture, long settlementId) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, fixture.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("0.00000000");
        assertThat(jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, fixture.reservationId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_settlement WHERE id=?",
                String.class, settlementId)).isEqualTo("PENDING");
    }

    private Fixture fixture(boolean boundReservation) {
        return fixture(boundReservation, false);
    }

    private Fixture fixture(boolean boundReservation, boolean explicitCommitment) {
        var suffix = "settle-tx-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        var orgId = lastId();
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,
                  version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00','2026-09-01 00:00:00','OPEN',0,0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = lastId();
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?, 'Settlement Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "project-" + suffix);
        var projectId = lastId();
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Settlement Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "service-" + suffix);
        var serviceId = lastId();
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,max_output_tokens,
                  created_at,updated_at)
                VALUES (?, 'Settlement Model','ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "model-" + suffix);
        var modelId = lastId();
        var providerCode = "MIMO-" + suffix;
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, "MiMo", "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "wire-" + suffix);
        var providerModelId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,
                  status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, "Settlement Account", suffix);
        var providerAccountId = lastId();
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = lastId();
        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?, 'INPUT_TOKEN',1,'1.00000000'),(?,?, 'OUTPUT_TOKEN',1,'0.40000000')
                """, orgId, pricingVersionId, orgId, pricingVersionId);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,
                  created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,? ,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(1), serviceId, projectId, projectId);
        var credentialId = lastId();
        var publicRequestId = fixedId("gwr");
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, publicRequestId, credentialId, serviceId, projectId, projectId,
                modelId, digest(2), digest(3), periodId);
        var requestId = lastId();
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,'COMPLETED',UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedId("grd"), providerAccountId, providerModelId,
                pricingVersionId);
        var attemptId = lastId();
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,status,
                  usage_effective_at,usage_effective_at_source,pricing_version_id,currency,
                  observed_at,created_at)
                VALUES (?,?,?,1,'FINAL',UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, requestId, attemptId, pricingVersionId);
        var usageFactId = lastId();
        jdbc.update("""
                INSERT INTO gateway_usage_dimension(org_id,usage_fact_id,dimension_code,quantity,provenance)
                VALUES (?,?, 'INPUT_TOKEN',1,'PROVIDER_FINAL'),
                       (?,?, 'OUTPUT_TOKEN',2,'PROVIDER_FINAL')
                """, orgId, usageFactId, orgId, usageFactId);
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                usageFactId, requestId);

        long budgetId = -1;
        long reservationId = -1;
        long commitmentId = -1;
        if (boundReservation) {
            jdbc.update("""
                    INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                      total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                    VALUES (?,?, 'PROJECT',?,'USD','1.00000000',0,?, 'ACTIVE',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """, orgId, periodId, projectId, explicitCommitment ? "0.50000000" : "0.00000000");
            budgetId = lastId();
            if (explicitCommitment) {
                jdbc.update("""
                        INSERT INTO budget_commitment(org_id,budget_id,status,requested_amount,
                          approved_amount,remaining_amount,version,created_at,updated_at)
                        VALUES (?,?,'ACTIVE','0.50000000','0.50000000','0.50000000',1,
                          UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                        """, orgId, budgetId);
                commitmentId = lastId();
            }
            jdbc.update("""
                    INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,billing_period_id,
                      budget_id,financial_scope_type,financial_scope_id,currency,reserved_amount,
                      commitment_id,commitment_backed_amount,status,version,expires_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,'PROJECT',?,'USD','1.00000000',?, ?, 'ACTIVE',0,
                      DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, periodId, budgetId, projectId,
                    commitmentId == -1 ? null : commitmentId,
                    explicitCommitment ? "0.50000000" : "0.00000000");
            reservationId = lastId();
        }
        return new Fixture(orgId, periodId, projectId, budgetId, reservationId, commitmentId);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static byte[] digest(int seed) {
        var result = new byte[32];
        for (var i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }

    private static String fixedId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "") + "00000";
    }

    private record Fixture(long orgId, long periodId, long projectId, long budgetId,
            long reservationId, long commitmentId) {
    }
}
