package com.aicostops.reconciliation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementFailureInjector;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementWorker;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.reconciliation.domain.PeriodCloseCheckResult;
import com.aicostops.reconciliation.domain.PeriodCloseRunStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(properties = {
        "aicostops.gateway.settlement.worker-enabled=true",
        "aicostops.gateway.settlement.poll-interval=1h"})
class PeriodCloseCoordinatorIntegrationTest extends AllocationApiTestSupport {

    @Autowired ReconciliationRunService reconciliationRuns;
    @Autowired PeriodCloseService close;
    @Autowired AuthorizationContextService authorizationContexts;
    @Autowired GatewaySettlementDiscoveryService settlements;
    @Autowired GatewaySettlementService settlementService;
    @Autowired GatewaySettlementWorker settlementWorker;

    @MockitoBean GatewaySettlementFailureInjector failureInjector;

    private long periodId;
    private AuthenticatedUser actor;

    @BeforeEach
    void closeSetup() {
        grantM6FinancePermissions("ALLOC_WORKER");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        actor = new AuthenticatedUser(actorUserId, 7);
    }

    @Test
    void blockedCloseReturnsPeriodOpenAndPersistsExactlyEightChecks() {
        var result = close.close(actor, periodId);

        assertThat(result.period().status().name()).isEqualTo("OPEN");
        assertThat(result.run().status()).isEqualTo(PeriodCloseRunStatus.BLOCKED);
        assertThat(result.run().attemptNo()).isEqualTo(1);
        assertThat(result.checks()).hasSize(8);
        assertThat(result.checks()).extracting(check -> check.blockerCode().name())
                .containsExactly(
                        "OPEN_IMPORTS", "UNRESOLVED_DUPLICATES", "UNALLOCATED_CHARGES",
                        "UNPOSTED_APPROVED_EXPENSES", "OPEN_MATERIAL_RECONCILIATION",
                        "PENDING_CORRECTIONS", "LEDGER_INTEGRITY",
                        "PENDING_GATEWAY_FINANCIAL_WORK");
        assertThat(result.checks()).anySatisfy(check -> {
            if (check.blockerCode().name().equals("OPEN_MATERIAL_RECONCILIATION")) {
                assertThat(check.result()).isEqualTo(PeriodCloseCheckResult.FAIL);
            }
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_close_check WHERE period_close_run_id=?",
                Long.class, result.run().id())).isEqualTo(8);
    }

    @Test
    void cleanCloseClosesPeriodAndResponseLossRetryDoesNotCreateSecondRun() {
        reconciliationRuns.run(actor, periodId);
        var first = close.close(actor, periodId);

        assertThat(first.period().status().name()).isEqualTo("CLOSED");
        assertThat(first.run().status()).isEqualTo(PeriodCloseRunStatus.CLOSED);
        assertThat(first.checks()).hasSize(8)
                .allMatch(check -> check.result() == PeriodCloseCheckResult.PASS);
        var runCount = closeRunCount();

        var replay = close.close(actor, periodId);
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(closeRunCount()).isEqualTo(runCount);
    }

    @Test
    void interruptedClosingResumesSameCheckingRun() {
        var context = authorizationContexts.fresh(actor);
        var begun = close.beginOrResume(context, periodId);

        assertThat(begun.period().status().name()).isEqualTo("CLOSING");
        assertThat(begun.run().status()).isEqualTo(PeriodCloseRunStatus.CHECKING);
        assertThat(closeRunCount()).isEqualTo(1);

        var resumed = close.close(actor, periodId);
        assertThat(resumed.run().id()).isEqualTo(begun.run().id());
        assertThat(resumed.run().status()).isEqualTo(PeriodCloseRunStatus.BLOCKED);
        assertThat(resumed.period().status().name()).isEqualTo("OPEN");
        assertThat(closeRunCount()).isEqualTo(1);
        assertThat(resumed.checks()).hasSize(8);
    }

    @Test
    void blockedRetryInSameGenerationUsesNextAttemptNumber() {
        var first = close.close(actor, periodId);
        var second = close.close(actor, periodId);

        assertThat(first.run().closeGeneration()).isZero();
        assertThat(second.run().closeGeneration()).isZero();
        assertThat(first.run().attemptNo()).isEqualTo(1);
        assertThat(second.run().attemptNo()).isEqualTo(2);
        assertThat(second.period().status().name()).isEqualTo("OPEN");
    }

    @Test
    void realCloseWaitsForSettlementAndThenEvaluatesTerminalGatewayTruth() throws Exception {
        var fixture = insertGatewayFixture();
        var settlement = settlements.discover(orgId).getFirst();
        reconciliationRuns.run(actor, periodId);

        var periodLocked = new CountDownLatch(1);
        var releaseSettlement = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("BILLING_PERIOD_LOCKED".equals(invocation.getArgument(0))) {
                periodLocked.countDown();
                assertThat(releaseSettlement.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(failureInjector).after(org.mockito.ArgumentMatchers.anyString());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var settlementFuture = executor.submit(
                    () -> settlementService.settle(orgId, settlement.id()));
            assertThat(periodLocked.await(10, TimeUnit.SECONDS)).isTrue();

            var closeFuture = executor.submit(() -> close.close(actor, periodId));
            Thread.sleep(250);
            assertThat(closeFuture.isDone()).isFalse();

            releaseSettlement.countDown();
            assertThat(settlementFuture.get(20, TimeUnit.SECONDS).settlement().status().name())
                    .isEqualTo("SETTLED");
            var closed = closeFuture.get(20, TimeUnit.SECONDS);
            assertThat(closed.period().status().name()).isEqualTo("CLOSED");
            assertThat(closed.run().status()).isEqualTo(PeriodCloseRunStatus.CLOSED);
            assertThat(closed.checks()).anySatisfy(check -> {
                if (check.blockerCode().name().equals("PENDING_GATEWAY_FINANCIAL_WORK")) {
                    assertThat(check.result()).isEqualTo(PeriodCloseCheckResult.PASS);
                }
            });
        }

        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
        assertThat(jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, fixture.reservationId())).isEqualTo("FINALIZED");
    }

    @Test
    void realCloseBlockThenDueRetrySettlesAfterClosingContention() {
        var fixture = insertGatewayFixture();
        var settlement = settlements.discover(orgId).getFirst();
        reconciliationRuns.run(actor, periodId);

        var begun = close.beginOrResume(authorizationContexts.fresh(actor), periodId);
        assertThat(begun.period().status().name()).isEqualTo("CLOSING");

        var contended = settlementService.settle(orgId, settlement.id());

        assertThat(contended.settlement().status().name()).isEqualTo("RETRYABLE_FAILED");
        assertThat(contended.settlement().lastErrorCode()).isEqualTo("PERIOD_CLOSING");
        var blocked = close.close(actor, periodId);
        assertThat(blocked.period().status().name()).isEqualTo("OPEN");
        assertThat(blocked.run().status()).isEqualTo(PeriodCloseRunStatus.BLOCKED);
        assertThat(blocked.checks()).anySatisfy(check -> {
            if (check.blockerCode().name().equals("PENDING_GATEWAY_FINANCIAL_WORK")) {
                assertThat(check.result()).isEqualTo(PeriodCloseCheckResult.FAIL);
            }
        });

        jdbc.update("UPDATE gateway_settlement SET next_attempt_at=UTC_TIMESTAMP(6) WHERE id=?",
                settlement.id());
        assertThat(settlementWorker.runOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_settlement WHERE id=?",
                String.class, settlement.id())).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, orgId)).isOne();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
        assertThat(jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, fixture.reservationId())).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void genuinelyClosedPeriodReconcilesSettlementWithoutFinancialMutation() {
        reconciliationRuns.run(actor, periodId);
        var closed = close.close(actor, periodId);
        assertThat(closed.period().status().name()).isEqualTo("CLOSED");

        var fixture = insertGatewayFixture();
        var settlement = settlements.discover(orgId).getFirst();
        var result = settlementService.settle(orgId, settlement.id());

        assertThat(result.settlement().status().name()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.settlement().lastErrorCode()).isEqualTo("BILLING_PERIOD_CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("0.00000000");
        assertThat(jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, fixture.reservationId())).isEqualTo("ACTIVE");
    }

    private long closeRunCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_close_run WHERE org_id=? AND billing_period_id=?",
                Long.class, orgId, periodId);
    }

    private void grantM6FinancePermissions(String roleCode) {
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code=? AND p.code IN (
                  'RECONCILIATION_READ','RECONCILIATION_RUN','RECONCILIATION_RESOLVE',
                  'PERIOD_READ','PERIOD_CLOSE','PERIOD_REOPEN')
                """, roleCode);
    }

    private GatewayFixture insertGatewayFixture() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var providerCode = "MIMO-" + suffix.substring(0, 8);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,max_output_tokens,
                  created_at,updated_at)
                VALUES (?, 'Close Gateway Model','ACTIVE',JSON_OBJECT(),1024,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "close-gateway-model-" + suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, "Close MiMo", "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "close-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,
                  status,metadata_json,created_at,updated_at)
                VALUES (?,?,? ,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, "Close Settlement Account", suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'INPUT_TOKEN',1,'1.00000000'),(?,?, 'OUTPUT_TOKEN',1,'0.40000000')
                """, orgId, pricingVersionId, orgId, pricingVersionId);
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Close Gateway Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "close-gateway-service-" + suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,
                  principal_type,organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,? ,?,'PROJECT',?,'REQUIRED','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, ("a" + suffix).substring(0, 12), digest(101), serviceId,
                projectId, projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "gwr_" + suffix, credentialId, serviceId, projectId, projectId,
                modelId, digest(102), digest(103), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,'COMPLETED',UTC_TIMESTAMP(6))
                """, orgId, requestId, "grd_" + suffix, providerAccountId, providerModelId,
                pricingVersionId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,status,
                  usage_effective_at,usage_effective_at_source,pricing_version_id,currency,
                  observed_at,created_at)
                VALUES (?,?,?,1,'FINAL',UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, requestId, attemptId, pricingVersionId);
        var usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_usage_dimension(org_id,usage_fact_id,dimension_code,quantity,provenance)
                VALUES (?,?, 'INPUT_TOKEN',1,'PROVIDER_FINAL'),
                       (?,?, 'OUTPUT_TOKEN',2,'PROVIDER_FINAL')
                """, orgId, usageFactId, orgId, usageFactId);
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                usageFactId, requestId);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?, 'PROJECT',?,'USD','100.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        var budgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,billing_period_id,
                  budget_id,financial_scope_type,financial_scope_id,currency,reserved_amount,
                  commitment_id,commitment_backed_amount,status,version,expires_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'PROJECT',?,'USD','1.00000000',NULL,0,'ACTIVE',0,
                  DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, requestId, attemptId, periodId, budgetId, projectId);
        var reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new GatewayFixture(requestId, budgetId, reservationId);
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((seed + i) % 251);
        }
        return bytes;
    }

    private record GatewayFixture(long requestId, long budgetId, long reservationId) {
    }
}
