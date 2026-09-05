package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.request.DispatchFenceService;
import com.aicostops.gateway.request.GatewayRequestService;
import com.aicostops.gateway.request.GatewayRequestService.AuthorizeCommand;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M12 reservation recovery on real MySQL: TTL is a recovery trigger, not
 * proof of no cost. A definitively pre-dispatch hold (RESERVED/VALIDATED +
 * PLANNED, no DISPATCH_INTENT) becomes RELEASED with the request moved to
 * FAILED_PRE_DISPATCH; anything that may have dispatched becomes
 * PENDING_HOLD and keeps holding money. Post-dispatch evidence is never
 * released. Recovery vs fence never yields DISPATCH_INTENT + RELEASED.
 */
@SpringBootTest
@Tag("integration")
class ReservationRecoveryIntegrationTest extends GatewayMySqlContainerSupport {

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

        recoveryService.recoverExpiredBlocking();

        assertThat(requestState(ids.requestId())).isEqualTo("FAILED_PRE_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt WHERE request_id=?", Long.class,
                ids.requestId())).isEqualTo(1L);
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
