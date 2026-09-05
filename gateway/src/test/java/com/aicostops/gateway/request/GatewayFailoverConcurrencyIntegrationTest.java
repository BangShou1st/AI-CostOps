package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-MySQL two-worker proof for durable route-attempt convergence. The
 * request row is the serialization authority; the coordinator independently
 * proves predecessor safety, effective-hold absence and candidate history.
 */
@SpringBootTest
@Tag("integration")
class GatewayFailoverConcurrencyIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RouteAttemptCoordinator coordinator;

    @Autowired
    private RequestIdentityService identity;

    private SeededEnv env;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void historicalCandidateCannotBePlannedAgainAfterSafeAAndB() {
        env = GatewayTestFixture.seed(jdbc, "coordinator-history-" + System.nanoTime(), HMAC_KEY, rawKey());
        var requestId = insertRequest("history");
        var policyId = policyId();
        var first = coordinator.plan(env.orgId(), requestId, policyId, "INITIAL_PRIMARY",
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId());
        coordinator.markSafe(env.orgId(), first.id(), ProviderSafetyReason.BUDGET_INSUFFICIENT_PRE_PROVIDER);

        var secondAccount = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "https://second.example/v1", 1);
        var secondModel = jdbc.queryForObject(
                "SELECT provider_model_id FROM routing_policy_candidate "
                        + "WHERE provider_account_id=?", Long.class, secondAccount);
        var secondPricing = jdbc.queryForObject(
                "SELECT id FROM pricing_version WHERE provider_account_id=?", Long.class, secondAccount);
        var second = coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                secondAccount, secondModel, secondPricing);
        coordinator.markSafe(env.orgId(), second.id(), ProviderSafetyReason.DNS_PRE_CONNECT);

        assertThatThrownBy(() -> coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId()))
                .isInstanceOf(RouteAttemptCoordinator.PlanRejectedException.class)
                .satisfies(error -> assertThat(((RouteAttemptCoordinator.PlanRejectedException) error).rejection())
                        .isEqualTo(RouteAttemptCoordinator.PlanRejection.CANDIDATE_ALREADY_ATTEMPTED));

        var thirdAccount = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "https://third.example/v1", 2);
        var thirdModel = jdbc.queryForObject(
                "SELECT provider_model_id FROM routing_policy_candidate "
                        + "WHERE provider_account_id=?", Long.class, thirdAccount);
        var thirdPricing = jdbc.queryForObject(
                "SELECT id FROM pricing_version WHERE provider_account_id=?", Long.class, thirdAccount);
        coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                thirdAccount, thirdModel, thirdPricing);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                + "WHERE org_id=? AND request_id=?", Integer.class, env.orgId(), requestId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                + "WHERE org_id=? AND request_id=? AND provider_account_id=?",
                Integer.class, env.orgId(), requestId, env.providerAccountId())).isOne();
    }

    @Test
    void activeAndPendingHoldForbidAttemptTwoUntilReleased() {
        for (var status : List.of("ACTIVE", "PENDING_HOLD")) {
            env = GatewayTestFixture.seed(jdbc, "coordinator-hold-" + status + System.nanoTime(), HMAC_KEY, rawKey());
            var requestId = insertRequest("hold-" + status);
            var policyId = policyId();
            var first = coordinator.plan(env.orgId(), requestId, policyId, "INITIAL_PRIMARY",
                    env.providerAccountId(), env.providerModelId(), env.pricingVersionId());
            coordinator.markSafe(env.orgId(), first.id(), ProviderSafetyReason.DNS_PRE_CONNECT);
            insertReservation(requestId, first.id(), status);
            var secondAccount = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                    "https://hold.example/v1", 1);
            var secondModel = jdbc.queryForObject(
                    "SELECT provider_model_id FROM routing_policy_candidate WHERE provider_account_id=?",
                    Long.class, secondAccount);
            var secondPricing = jdbc.queryForObject(
                    "SELECT id FROM pricing_version WHERE provider_account_id=?", Long.class, secondAccount);

            assertThatThrownBy(() -> coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                    secondAccount, secondModel, secondPricing))
                    .isInstanceOf(RouteAttemptCoordinator.PlanRejectedException.class)
                    .satisfies(error -> assertThat(((RouteAttemptCoordinator.PlanRejectedException) error).rejection())
                            .isEqualTo(RouteAttemptCoordinator.PlanRejection.EFFECTIVE_RESERVATION_REMAINS));

            jdbc.update("UPDATE budget_reservation SET status='RELEASED', version=version+1, "
                    + "released_at=UTC_TIMESTAMP(6) WHERE request_id=?", requestId);
            var legal = coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                    secondAccount, secondModel, secondPricing);
            assertThat(legal.attemptNo()).isEqualTo(2);
            GatewayTestFixture.clean(jdbc);
        }
        env = null;
    }

    @RepeatedTest(5)
    void twoWorkersPlanningTheSameNextRouteCreateExactlyOneAttempt() throws Exception {
        env = GatewayTestFixture.seed(jdbc, "coordinator-race-" + System.nanoTime(), HMAC_KEY, rawKey());
        var requestId = insertRequest("race");
        var policyId = policyId();
        var first = coordinator.plan(env.orgId(), requestId, policyId, "INITIAL_PRIMARY",
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId());
        coordinator.markSafe(env.orgId(), first.id(), ProviderSafetyReason.DNS_PRE_CONNECT);
        var secondAccount = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "https://race.example/v1", 1);
        var secondModel = jdbc.queryForObject(
                "SELECT provider_model_id FROM routing_policy_candidate WHERE provider_account_id=?",
                Long.class, secondAccount);
        var secondPricing = jdbc.queryForObject(
                "SELECT id FROM pricing_version WHERE provider_account_id=?", Long.class, secondAccount);

        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var workers = List.of(
                    pool.submit(() -> planAfterBarrier(requestId, policyId, secondAccount, secondModel,
                            secondPricing, ready, go)),
                    pool.submit(() -> planAfterBarrier(requestId, policyId, secondAccount, secondModel,
                            secondPricing, ready, go)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var outcomes = new ArrayList<PlanOutcome>();
            for (var worker : workers) outcomes.add(worker.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).extracting(PlanOutcome::winner).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                    + "WHERE org_id=? AND request_id=? AND attempt_no=2",
                    Integer.class, env.orgId(), requestId)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(5)
    void concurrentWorkersCannotWinTheSameHistoricalDuplicateCandidate() throws Exception {
        env = GatewayTestFixture.seed(jdbc, "coordinator-duplicate-" + System.nanoTime(), HMAC_KEY, rawKey());
        var requestId = insertRequest("duplicate");
        var policyId = policyId();
        var first = coordinator.plan(env.orgId(), requestId, policyId, "INITIAL_PRIMARY",
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId());
        coordinator.markSafe(env.orgId(), first.id(), ProviderSafetyReason.DNS_PRE_CONNECT);
        var secondAccount = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "https://duplicate.example/v1", 1);
        var secondModel = jdbc.queryForObject(
                "SELECT provider_model_id FROM routing_policy_candidate WHERE provider_account_id=?",
                Long.class, secondAccount);
        var secondPricing = jdbc.queryForObject(
                "SELECT id FROM pricing_version WHERE provider_account_id=?", Long.class, secondAccount);
        var second = coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                secondAccount, secondModel, secondPricing);
        coordinator.markSafe(env.orgId(), second.id(), ProviderSafetyReason.DNS_PRE_CONNECT);

        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var workers = List.of(
                    pool.submit(() -> planAfterBarrier(requestId, policyId, env.providerAccountId(),
                            env.providerModelId(), env.pricingVersionId(), ready, go)),
                    pool.submit(() -> planAfterBarrier(requestId, policyId, env.providerAccountId(),
                            env.providerModelId(), env.pricingVersionId(), ready, go)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var outcomes = List.of(workers.get(0).get(30, TimeUnit.SECONDS), workers.get(1).get(30, TimeUnit.SECONDS));
            assertThat(outcomes).extracting(PlanOutcome::winner).containsExactlyInAnyOrder(false, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                    + "WHERE org_id=? AND request_id=? AND provider_account_id=?",
                    Integer.class, env.orgId(), requestId, env.providerAccountId())).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void terminalCancellationCannotAllocateACloserAttempt() {
        env = GatewayTestFixture.seed(jdbc, "coordinator-terminal-" + System.nanoTime(), HMAC_KEY, rawKey());
        var requestId = insertRequest("terminal");
        var policyId = policyId();
        var first = coordinator.plan(env.orgId(), requestId, policyId, "INITIAL_PRIMARY",
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId());
        coordinator.markSafe(env.orgId(), first.id(), ProviderSafetyReason.CLIENT_CANCEL_BEFORE_DISPATCH);
        jdbc.update("UPDATE gateway_request SET state='FAILED_PRE_DISPATCH', terminal_at=UTC_TIMESTAMP(6) "
                + "WHERE id=? AND org_id=?", requestId, env.orgId());

        var secondAccount = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "https://terminal.example/v1", 1);
        var secondModel = jdbc.queryForObject(
                "SELECT provider_model_id FROM routing_policy_candidate WHERE provider_account_id=?",
                Long.class, secondAccount);
        var secondPricing = jdbc.queryForObject(
                "SELECT id FROM pricing_version WHERE provider_account_id=?", Long.class, secondAccount);

        assertThatThrownBy(() -> coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                secondAccount, secondModel, secondPricing))
                .isInstanceOf(RouteAttemptCoordinator.PlanRejectedException.class)
                .satisfies(error -> assertThat(((RouteAttemptCoordinator.PlanRejectedException) error).rejection())
                        .isEqualTo(RouteAttemptCoordinator.PlanRejection.REQUEST_NOT_ROUTEABLE));
    }

    private PlanOutcome planAfterBarrier(long requestId, long policyId, long accountId,
            long modelId, long pricingId, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(10, TimeUnit.SECONDS)) return new PlanOutcome(false, "barrier-timeout");
            var planned = coordinator.plan(env.orgId(), requestId, policyId, "SAFE_FAILOVER",
                    accountId, modelId, pricingId);
            return new PlanOutcome(true, "attempt-" + planned.attemptNo());
        } catch (RouteAttemptCoordinator.PlanRejectedException ex) {
            return new PlanOutcome(false, ex.rejection().name());
        } catch (RuntimeException ex) {
            return new PlanOutcome(false, ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new PlanOutcome(false, "interrupted");
        }
    }

    private long insertRequest(String suffix) {
        var idem = identity.idempotencyKeyDigest("coordinator-" + suffix + System.nanoTime());
        var fingerprint = identity.requestFingerprint(
                ("{\"model\":\"default-chat\",\"suffix\":\"" + suffix + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,logical_model_id,
                  api_surface,idempotency_key_digest,request_fingerprint,request_hmac_version,state,
                  billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',? ,?,'CHAT_COMPLETIONS',?,?,1,
                  'VALIDATED',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
              """, env.orgId(), identity.newPublicRequestId(), env.credentialId(),
                env.serviceIdentityId(), env.projectId(), env.projectId(), env.modelId(), idem, fingerprint);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long policyId() {
        return jdbc.queryForObject("SELECT id FROM routing_policy WHERE org_id=? AND project_id IS NULL "
                + "AND model_id=? AND status='ACTIVE'", Long.class, env.orgId(), env.modelId());
    }

    private void insertReservation(long requestId, long attemptId, String status) {
        jdbc.update("""
                INSERT INTO budget_reservation(
                  org_id,request_id,route_attempt_id,billing_period_id,budget_id,
                  financial_scope_type,financial_scope_id,currency,reserved_amount,
                  commitment_id,commitment_backed_amount,status,version,expires_at,
                  created_at,updated_at,released_at,finalized_at)
                VALUES (?,?,?,?,?,'PROJECT',?,'USD',?,NULL,0,?,0,
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, env.orgId(), requestId, attemptId, env.periodId(), env.budgetId(),
                env.projectId(), new BigDecimal("1.00000000"), status);
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private record PlanOutcome(boolean winner, String detail) {
    }
}
