package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService;
import com.aicostops.gateway.metering.GatewayUsageObservation;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.request.DispatchFenceService;
import com.aicostops.gateway.request.GatewayRequestService;
import com.aicostops.gateway.request.GatewayRequestService.AuthorizeCommand;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M12 reservation recovery on real MySQL: TTL is a recovery trigger, not
 * proof of no cost. A definitively pre-dispatch hold (RESERVED/VALIDATED +
 * PLANNED, no DISPATCH_INTENT) becomes RELEASED with the request moved to
 * FAILED_PRE_DISPATCH; anything that may have dispatched becomes
 * PENDING_HOLD and keeps holding money. Post-dispatch evidence is never
 * released. Recovery vs fence never yields DISPATCH_INTENT + RELEASED.
 */
@SpringBootTest(properties = "spring.data.redis.timeout=200ms")
@Tag("integration")
class ReservationRecoveryIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String EXPIRY_KEY = "aicostops:v2:gateway:reservation-expiry";
    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private ReservationRecoveryService recoveryService;

    @Autowired
    private SafeReservationReleaseService safeReleaseService;

    @Autowired
    private BudgetReservationService reservationService;

    @Autowired
    private GatewayRequestService requestService;

    @Autowired
    private DispatchFenceService dispatchFenceService;

    @Autowired
    private BudgetReservationMapper reservationMapper;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private com.aicostops.gateway.request.RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationDelayQueue delayQueue;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private BlockingIoScheduler blockingIo;

    @Autowired
    private GatewayUsageFinalizationService finalization;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void preDispatchExpiredHoldIsReleasedAndRequestFails() {
        var env = GatewayTestFixture.seed(jdbc, "rec-pre", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-pre-key");
        var admission = reservationService.admit(admissionCommand(
                required(env), env, ids.requestId(), ids.attemptId())).block();
        expireNow(admission.reservationId());

        recoveryService.recoverExpiredBlocking();

        assertThat(reservationStatus(admission.reservationId())).isEqualTo("RELEASED");
        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("0.00000000");

        // Second recovery pass must be a no-op and never double-decrement.
        recoveryService.recoverExpiredBlocking();
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("0.00000000");
    }

    @Test
    void twoPollersRecoverDueHoldWithinTwoSeconds() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "rec-queue", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-queue-key");
        var admission = reservationService.admit(admissionCommand(
                required(env), env, ids.requestId(), ids.attemptId())).block();
        long id = admission.reservationId();
        assertThat(redis.opsForZSet().score(EXPIRY_KEY, Long.toString(id)))
                .isEqualTo((double) admission.expiresAt().toEpochMilli());

        // An early Redis notification must be requeued using MySQL's expiry.
        delayQueue.enqueue(id, Instant.now().minusSeconds(1));
        recoveryService.recoverQueuedBlocking();
        assertThat(reservationStatus(id)).isEqualTo("ACTIVE");
        assertThat(redis.opsForZSet().score(EXPIRY_KEY, Long.toString(id))).isGreaterThan(
                (double) Instant.now().toEpochMilli());

        jdbc.update("UPDATE budget_reservation SET expires_at=UTC_TIMESTAMP(6) WHERE id=?", id);
        var dueAt = Instant.now();
        delayQueue.enqueue(id, dueAt);
        var first = new ReservationRecoveryScheduler(recoveryService, blockingIo);
        var second = new ReservationRecoveryScheduler(recoveryService, blockingIo);
        try (var pollers = Executors.newScheduledThreadPool(2)) {
            pollers.schedule(first::recoverQueued, 1, TimeUnit.SECONDS);
            pollers.schedule(second::recoverQueued, 1, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ("ACTIVE".equals(reservationStatus(id)) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }
        assertThat(reservationStatus(id)).isEqualTo("RELEASED");
        var recoveryMicros = jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND, expires_at, released_at)
                FROM budget_reservation WHERE id=?
                """, Long.class, id);
        assertThat(recoveryMicros).isBetween(0L, 2_000_000L);
        System.out.println("HC-04 queued recovery after " + recoveryMicros + " us (MySQL timestamps)");
        assertThat(jdbc.queryForObject("SELECT version FROM budget_reservation WHERE id=?",
                Long.class, id)).isEqualTo(1L);
        assertThat(redis.opsForZSet().score(EXPIRY_KEY, Long.toString(id))).isNull();
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("0");
    }

    @Test
    void concurrentRedisClaimersReceiveDisjointBatches() throws Exception {
        var now = Instant.now();
        for (long id = 1; id <= 20; id++) {
            delayQueue.enqueue(id, now);
        }
        var secondConnection = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        secondConnection.afterPropertiesSet();
        var secondQueue = new ReservationDelayQueue(new StringRedisTemplate(secondConnection));
        var start = new CountDownLatch(1);
        try (var pollers = Executors.newFixedThreadPool(2)) {
            var first = pollers.submit(() -> {
                start.await();
                return delayQueue.claimDue(now, 10);
            });
            var second = pollers.submit(() -> {
                start.await();
                return secondQueue.claimDue(now, 10);
            });
            start.countDown();
            var claimed = new java.util.ArrayList<>(first.get(5, TimeUnit.SECONDS));
            claimed.addAll(second.get(5, TimeUnit.SECONDS));
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList());
        } finally {
            secondConnection.destroy();
        }
        assertThat(delayQueue.claimDue(now, 20)).isEmpty();
    }

    @Test
    void mysqlScanRecoversAfterQueueLossAndDuringRedisOutage() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "rec-lost", HMAC_KEY, rawKey());
        var first = insertValidatedRequest(env, "rec-lost-first");
        var firstHold = reservationService.admit(admissionCommand(
                required(env), env, first.requestId(), first.attemptId())).block();
        expireNow(firstHold.reservationId());
        assertThat(redis.delete(EXPIRY_KEY)).isTrue();
        recoveryService.recoverQueuedBlocking();
        assertThat(reservationStatus(firstHold.reservationId())).isEqualTo("ACTIVE");

        var second = insertValidatedRequest(env, "rec-lost-second");
        assertThat(REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL").getStdout())
                .contains("OK");
        try {
            var secondHold = reservationService.admit(admissionCommand(
                    required(env), env, second.requestId(), second.attemptId())).block();
            assertThat(secondHold.outcome()).isEqualTo(BudgetReservationService.AdmissionOutcome.RESERVED);
            expireNow(secondHold.reservationId());
            recoveryService.recoverQueuedBlocking();
            recoveryService.recoverExpiredBlocking();
            assertThat(reservationStatus(firstHold.reservationId())).isEqualTo("RELEASED");
            assertThat(reservationStatus(secondHold.reservationId())).isEqualTo("RELEASED");
            assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("0");
        } finally {
            REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
        }
    }

    @Test
    void postDispatchExpiredHoldBecomesPendingHold() {
        var env = GatewayTestFixture.seed(jdbc, "rec-post", HMAC_KEY, rawKey());
        var result = requestService.authorizeAndFence(authorizeCommand(
                optional(env), env, "rec-post-key")).block();
        var reservationId = jdbc.queryForObject("""
                SELECT id FROM budget_reservation WHERE org_id=? AND route_attempt_id=?
                """, Long.class, env.orgId(), result.routeAttemptId());
        expireNow(reservationId);

        recoveryService.recoverExpiredBlocking();

        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_HOLD");
        assertThat(requestState(result.requestId())).isEqualTo("DISPATCH_INTENT");
        // ACTIVE -> PENDING_HOLD stays an effective hold, so reserved_amount stays intact.
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("31.94880000");
    }

    @Test
    void postDispatchFailureClearsTheWakeupButRetainsTheEffectiveHold() {
        var env = GatewayTestFixture.seed(jdbc, "rec-failure", HMAC_KEY, rawKey());
        var dispatch = requestService.authorizeAndFence(authorizeCommand(
                required(env), env, "rec-failure-key")).block();
        var reservation = reservationMapper.findByRouteAttempt(env.orgId(), dispatch.routeAttemptId());
        var member = Long.toString(reservation.id());
        assertThat(redis.opsForZSet().score(EXPIRY_KEY, member)).isNotNull();

        finalization.finalizeFailure(dispatch.requestId(), env.orgId(), dispatch.routeAttemptId(),
                GatewayUsageObservation.noUsage(Instant.now()).withDispatched(true),
                GatewayUsageFinalizationService.TransportFailure.FAILED).block();

        assertThat(reservationStatus(reservation.id())).isEqualTo("PENDING_HOLD");
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo(reservation.reservedAmount());
        assertThat(redis.opsForZSet().score(EXPIRY_KEY, member)).isNull();
    }

    @Test
    void safeAttemptExpiredHoldIsReleasedEvenWhenRequestWasAlreadyActive() {
        var env = GatewayTestFixture.seed(jdbc, "rec-safe-active", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-safe-active-key");
        var admission = reservationService.admit(admissionCommand(
                required(env), env, ids.requestId(), ids.attemptId())).block();
        requestMapper.markAttemptSafe(ids.attemptId(), env.orgId(), "DNS_PRE_CONNECT");
        jdbc.update("UPDATE gateway_request SET state='UPSTREAM_ACTIVE' WHERE id=?", ids.requestId());
        expireNow(admission.reservationId());

        recoveryService.recoverExpiredBlocking();

        assertThat(reservationStatus(admission.reservationId())).isEqualTo("RELEASED");
        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
    }

    @Test
    void safeReleasedGapConvergesToTerminalWithoutBackgroundDispatch() {
        var env = GatewayTestFixture.seed(jdbc, "rec-safe-released", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-safe-released-key");
        reservationService.admit(admissionCommand(
                required(env), env, ids.requestId(), ids.attemptId())).block();
        requestMapper.markAttemptSafe(ids.attemptId(), env.orgId(), "DNS_PRE_CONNECT");
        jdbc.update("UPDATE gateway_request SET state='UPSTREAM_ACTIVE' WHERE id=?", ids.requestId());

        var release = safeReleaseService.releaseForSafeAttempt(
                env.orgId(), ids.requestId(), ids.attemptId(), env.periodId());
        assertThat(release.status()).isEqualTo(SafeReservationReleaseService.ReleaseStatus.RELEASED);
        assertThat(redis.opsForZSet().zCard(EXPIRY_KEY)).isZero();
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("0.00000000");

        // Repeated safe release must never double-decrement.
        var duplicateRelease = safeReleaseService.releaseForSafeAttempt(
                env.orgId(), ids.requestId(), ids.attemptId(), env.periodId());
        assertThat(duplicateRelease.status()).isEqualTo(SafeReservationReleaseService.ReleaseStatus.NONE);
        assertThat(budgetReservedAmount(env.budgetId())).isEqualByComparingTo("0.00000000");

        recoveryService.recoverExpiredBlocking();

        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt WHERE request_id=?", Long.class,
                ids.requestId())).isEqualTo(1L);
    }

    @Test
    void stalePreDispatchRequestWithoutReservationConvergesToTerminal() {
        var env = GatewayTestFixture.seed(jdbc, "rec-orphan", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-orphan-key");
        // Simulates a crash between the PLANNED attempt and TX1 admission:
        // the request exists, the attempt is PLANNED, but no reservation was
        // ever created. The expired-hold scan cannot see it.
        jdbc.update("""
                UPDATE gateway_request
                SET created_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 16 MINUTE)
                WHERE id=?
                """, ids.requestId());

        recoveryService.recoverExpiredBlocking();

        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE request_id=?",
                Long.class, ids.requestId())).isZero();
    }

    @Test
    void freshPreDispatchRequestWithoutReservationIsNotRecovered() {
        var env = GatewayTestFixture.seed(jdbc, "rec-orphan-fresh", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-orphan-fresh-key");

        recoveryService.recoverExpiredBlocking();

        // A request younger than the reservation TTL may still be in flight.
        assertThat(requestState(ids.requestId())).isEqualTo("VALIDATED");
    }

    @Test
    void staleSafeAndPlannedRequestWithoutReservationConvergesToTerminal() {
        var env = GatewayTestFixture.seed(jdbc, "rec-orphan-safe", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-orphan-safe-key");
        requestMapper.markAttemptSafe(ids.attemptId(), env.orgId(), "DNS_PRE_CONNECT");
        requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                env.orgId(), ids.requestId(), 2, identityService.newRouteDecisionId(), null,
                env.providerAccountId(), env.providerModelId(), null, env.pricingVersionId(), null));
        jdbc.update("""
                UPDATE gateway_request
                SET state='UPSTREAM_ACTIVE', created_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 16 MINUTE)
                WHERE id=?
                """, ids.requestId());

        recoveryService.recoverExpiredBlocking();

        // SAFE + PLANNED with no reservation: no Provider I/O can have billed.
        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
    }

    @Test
    void staleDispatchIntentWithOnlyPlannedAttemptConvergesToTerminal() {
        var env = GatewayTestFixture.seed(jdbc, "rec-orphan-intent", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-orphan-intent-key");
        jdbc.update("""
                UPDATE gateway_request
                SET state='DISPATCH_INTENT', created_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 16 MINUTE)
                WHERE id=?
                """, ids.requestId());

        recoveryService.recoverExpiredBlocking();

        // No reservation and the only attempt is still PLANNED: TX2 never
        // produced dispatch evidence, so the request is safely terminal.
        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
    }

    @Test
    void dispatchedAttemptIsNeverRecoveredAsStalePreDispatch() {
        var env = GatewayTestFixture.seed(jdbc, "rec-orphan-dispatched", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-orphan-dispatched-key");
        requestMapper.markRequestDispatchIntent(ids.requestId(), env.orgId(), env.periodId());
        requestMapper.markRouteAttemptDispatchIntent(ids.attemptId(), env.orgId());
        jdbc.update("""
                UPDATE gateway_request
                SET created_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 16 MINUTE)
                WHERE id=?
                """, ids.requestId());

        recoveryService.recoverExpiredBlocking();

        // A dispatched attempt may have reached the Provider; never release it
        // through the pre-dispatch orphan path.
        assertThat(requestState(ids.requestId())).isEqualTo("DISPATCH_INTENT");
    }

    @Test
    void recoveryFirstReleasesThenFenceMustFail() {
        var env = GatewayTestFixture.seed(jdbc, "rec-race", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-race-key");
        var admission = reservationService.admit(admissionCommand(
                required(env), env, ids.requestId(), ids.attemptId())).block();
        expireNow(admission.reservationId());

        recoveryService.recoverExpiredBlocking();
        assertThat(reservationStatus(admission.reservationId())).isEqualTo("RELEASED");

        // The fence now observes no ACTIVE reservation and must fail: never
        // DISPATCH_INTENT + RELEASED.
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
            dispatchFenceService.commitDispatchFence(
                    env.orgId(), ids.requestId(), ids.attemptId(), env.periodId(), admission);
        } catch (RuntimeException ex) {
            failure.set(ex);
        }
        assertThat(failure.get()).isNotNull();
        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
    }

    @Test
    void fenceFirstKeepsReservationEconomicallyHeld() {
        var env = GatewayTestFixture.seed(jdbc, "rec-fence", HMAC_KEY, rawKey());
        var result = requestService.authorizeAndFence(authorizeCommand(
                optional(env), env, "rec-fence-key")).block();
        var reservationId = jdbc.queryForObject("""
                SELECT id FROM budget_reservation WHERE org_id=? AND route_attempt_id=?
                """, Long.class, env.orgId(), result.routeAttemptId());
        expireNow(reservationId);

        // The fence already won: recovery must NOT release the hold.
        recoveryService.recoverExpiredBlocking();

        assertThat(reservationStatus(reservationId)).isEqualTo("PENDING_HOLD");
        assertThat(requestState(result.requestId())).isEqualTo("DISPATCH_INTENT");
    }

    @Test
    void staleVersionCannotReleaseNewerState() {
        var env = GatewayTestFixture.seed(jdbc, "rec-stale", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "rec-stale-key");
        var admission = reservationService.admit(admissionCommand(
                required(env), env, ids.requestId(), ids.attemptId())).block();
        expireNow(admission.reservationId());

        // Concurrently move the hold forward (simulates a fence/recovery race
        // winner): a stale recovery view must converge to SKIP, never release.
        jdbc.update("UPDATE budget_reservation SET status='PENDING_HOLD', version=version+1 WHERE id=?",
                admission.reservationId());

        recoveryService.recoverExpiredBlocking();

        assertThat(reservationStatus(admission.reservationId())).isEqualTo("PENDING_HOLD");
    }

    private RequestIds insertValidatedRequest(SeededEnv env, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"x-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject("""
                SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, env.orgId());
        requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                env.orgId(), requestId, identityService.newRouteDecisionId(),
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);
        return new RequestIds(requestId, attemptId);
    }

    private void expireNow(long reservationId) {
        jdbc.update("UPDATE budget_reservation SET expires_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id=?",
                reservationId);
    }

    private String reservationStatus(long reservationId) {
        return jdbc.queryForObject("SELECT status FROM budget_reservation WHERE id=?",
                String.class, reservationId);
    }

    private String requestState(long requestId) {
        return jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=?",
                String.class, requestId);
    }

    private java.math.BigDecimal budgetReservedAmount(long budgetId) {
        return jdbc.queryForObject("SELECT reserved_amount FROM budget WHERE id=?",
                java.math.BigDecimal.class, budgetId);
    }

    private AdmissionCommand admissionCommand(
            GatewayPrincipal principal, SeededEnv env, long requestId, long attemptId) {
        return new AdmissionCommand(principal, requestId, attemptId, env.periodId(),
                env.pricingVersionId(), "USD", 8192, -1L);
    }

    private AuthorizeCommand authorizeCommand(
            GatewayPrincipal principal, SeededEnv env, String idempotencyKey) {
        return new AuthorizeCommand(principal, env.modelId(),
                "{\"model\":\"default-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                        .getBytes(StandardCharsets.UTF_8),
                idempotencyKey, 8192);
    }

    private GatewayPrincipal required(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
    }

    private GatewayPrincipal optional(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private record RequestIds(long requestId, long attemptId) {
    }
}
