package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-MySQL streaming SAFE failover cancellation proof. The orchestrator's
 * cancellation and post-TX2 observer seams are controlled by latches, so the
 * two financial windows do not depend on sleep timing or network scheduling.
 */
@SpringBootTest
@Tag("integration")
class GatewayStreamingSafeFailoverCancellationIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String RAW_KEY = "aic_0123456789ab_" + "A".repeat(43);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayRequestOrchestrator orchestrator;

    @Autowired
    private GatewayUsageFinalizationService usageFinalization;

    private SeededEnv env;
    private ExecutorService workers;

    @BeforeEach
    void seed() {
        env = GatewayTestFixture.seed(jdbc, "stream-cancel-" + System.nanoTime(), HMAC_KEY, RAW_KEY);
    }

    @AfterEach
    void clean() {
        if (workers != null) {
            workers.shutdownNow();
        }
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void clientCancellationAfterASafeReleaseBeforeBTx2StaysNoBillable() throws Exception {
        var secondAccountId = addCandidate("stream-cancel-before-b", 1);
        addCandidate("stream-cancel-before-c", 2);
        var prepared = prepareInitial("stream-cancel-before-b-1");
        var firstAttemptId = prepared.routeAttemptId();
        var requestId = prepared.requestId();
        var canceled = new AtomicBoolean(false);
        var providerCalls = new AtomicInteger();
        var releaseCommitted = new CountDownLatch(1);
        var allowCancellation = new CountDownLatch(1);
        var firstCancellationCheck = new AtomicBoolean(true);

        BooleanSupplier cancellation = () -> {
            if (firstCancellationCheck.compareAndSet(true, false)) {
                releaseCommitted.countDown();
                await(allowCancellation);
            }
            return canceled.get();
        };

        var future = runSafeAdvance(prepared, cancellation, ignored -> { });
        assertThat(releaseCommitted.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(status(firstAttemptId)).isEqualTo("SAFE_NO_BILLABLE_EXECUTION");
        assertThat(reservationStatus(firstAttemptId)).isEqualTo("RELEASED");

        // Client cancellation after durable SAFE + RELEASED but before the
        // next candidate can plan/admit/commit TX2.
        canceled.set(true);
        allowCancellation.countDown();
        var result = future.get(20, TimeUnit.SECONDS);
        orchestrator.convergeSafeTerminal(result).block();
        if (!canceled.get() && result.routeAttemptId() != firstAttemptId) {
            providerCalls.incrementAndGet();
        }

        assertThat(requestState(requestId)).isEqualTo("FAILED_PRE_DISPATCH");
        assertThat(status(firstAttemptId)).isEqualTo("SAFE_NO_BILLABLE_EXECUTION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=? AND route_attempt_id=?",
                Integer.class, env.orgId(), firstAttemptId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_settlement WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
        assertThat(reservationStatus(firstAttemptId)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                + "WHERE org_id=? AND status='BILLABLE_POSSIBLE'", Integer.class, env.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                + "WHERE org_id=? AND provider_account_id=?", Integer.class, env.orgId(), secondAccountId)).isZero();
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void clientCancellationAfterBTx2KeepsBConservativeWithoutCFailover() throws Exception {
        addCandidate("stream-cancel-after-b", 1);
        var thirdAccountId = addCandidate("stream-cancel-after-c", 2);
        var prepared = prepareInitial("stream-cancel-after-b-1");
        var firstAttemptId = prepared.routeAttemptId();
        var requestId = prepared.requestId();
        var canceled = new AtomicBoolean(false);
        var providerCalls = new AtomicInteger();
        var dispatchCommitted = new CountDownLatch(1);
        var allowCancellation = new CountDownLatch(1);
        AtomicReference<GatewayRequestOrchestrator.PreparedDispatch> current = new AtomicReference<>(prepared);

        Consumer<GatewayRequestOrchestrator.PreparedDispatch> onDispatchCommitted = next -> {
            // prepareNextSafe calls this observer only after B's TX2 has
            // durably committed. Pause here to model client cancellation in
            // the exact post-dispatch/pre-current-install window.
            dispatchCommitted.countDown();
            await(allowCancellation);
            current.set(next);
        };

        var future = runSafeAdvance(prepared, canceled::get, onDispatchCommitted);
        assertThat(dispatchCommitted.await(20, TimeUnit.SECONDS)).isTrue();
        var secondAttemptId = attemptId(2);
        assertThat(status(firstAttemptId)).isEqualTo("SAFE_NO_BILLABLE_EXECUTION");
        assertThat(reservationStatus(firstAttemptId)).isEqualTo("RELEASED");
        assertThat(status(secondAttemptId)).isEqualTo("DISPATCH_INTENT");
        assertThat(reservationStatus(secondAttemptId)).isEqualTo("ACTIVE");

        // Client cancellation happens after B TX2, so B must be finalized by
        // the conservative post-dispatch path and cannot be treated as SAFE.
        canceled.set(true);
        allowCancellation.countDown();
        var result = future.get(20, TimeUnit.SECONDS);
        assertThat(current.get().routeAttemptId()).isEqualTo(secondAttemptId);
        usageFinalization.finalizeFailure(
                requestId, env.orgId(), current.get().routeAttemptId(),
                com.aicostops.gateway.metering.GatewayUsageObservation.noUsage(null).withDispatched(true),
                GatewayUsageFinalizationService.TransportFailure.CANCELED).block();
        if (!canceled.get()) {
            providerCalls.incrementAndGet();
        }

        assertThat(result.routeAttemptId()).isEqualTo(secondAttemptId);
        assertThat(requestState(requestId)).isEqualTo("CANCELED_AFTER_DISPATCH");
        assertThat(status(firstAttemptId)).isEqualTo("SAFE_NO_BILLABLE_EXECUTION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=? AND route_attempt_id=?",
                Integer.class, env.orgId(), firstAttemptId)).isZero();
        assertThat(status(secondAttemptId)).isEqualTo("BILLABLE_POSSIBLE");
        assertThat(reservationStatus(secondAttemptId)).isEqualTo("PENDING_HOLD");
        assertThat(reservationStatus(secondAttemptId)).isNotEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt "
                + "WHERE org_id=? AND provider_account_id=?", Integer.class, env.orgId(), thirdAccountId)).isZero();
        assertThat(providerCalls).hasValue(0);
    }

    private Future<GatewayRequestOrchestrator.PreparedDispatch> runSafeAdvance(
            GatewayRequestOrchestrator.PreparedDispatch prepared,
            BooleanSupplier canceled,
            Consumer<GatewayRequestOrchestrator.PreparedDispatch> onDispatchCommitted) {
        workers = Executors.newSingleThreadExecutor();
        return workers.submit(() -> orchestrator.prepareNextSafe(
                prepared, safePreConnectFailure(), canceled, onDispatchCommitted).block());
    }

    private GatewayRequestOrchestrator.PreparedDispatch prepareInitial(String idempotencyKey) {
        var principal = new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
        var body = ("{\"model\":\"" + env.modelKey()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}")
                .getBytes(StandardCharsets.UTF_8);
        var command = new GatewayRequestService.AuthorizeCommand(
                principal, env.modelId(), body, idempotencyKey, 16, true);
        return orchestrator.prepareInitial(command, true).block();
    }

    private ProviderExecutionException safePreConnectFailure() {
        return new ProviderExecutionException(
                ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                ProviderSafetyReason.DNS_PRE_CONNECT,
                ProviderHealthSignal.QUALIFYING_FAILURE,
                null, null, false, null);
    }

    private long addCandidate(String suffix, int priority) {
        return GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "https://" + suffix + ".invalid/v1", priority);
    }

    private long attemptId(int attemptNo) {
        return jdbc.queryForObject("SELECT id FROM gateway_route_attempt WHERE org_id=? AND attempt_no=?",
                Long.class, env.orgId(), attemptNo);
    }

    private String status(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM gateway_route_attempt WHERE org_id=? AND id=?",
                String.class, env.orgId(), attemptId);
    }

    private String reservationStatus(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM budget_reservation WHERE org_id=? AND route_attempt_id=?",
                String.class, env.orgId(), attemptId);
    }

    private String requestState(long requestId) {
        return jdbc.queryForObject("SELECT state FROM gateway_request WHERE org_id=? AND id=?",
                String.class, env.orgId(), requestId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("deterministic cancellation barrier timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("deterministic cancellation barrier interrupted", ex);
        }
    }
}
