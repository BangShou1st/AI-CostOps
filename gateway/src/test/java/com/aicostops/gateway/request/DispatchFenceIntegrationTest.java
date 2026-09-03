package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.request.GatewayRequestService.AuthorizeCommand;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AIC-096 period-close safety on real MySQL: Close and DISPATCH_INTENT
 * serialize on the same BillingPeriod lock. If Close wins, the fence rejects
 * before any Provider I/O and the request stays pre-dispatch; if dispatch
 * wins, the durable DISPATCH_INTENT + billing_period_id state blocks Close.
 */
@SpringBootTest
@Tag("integration")
class DispatchFenceIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private GatewayRequestService requestService;

    @Autowired
    private DispatchFenceService dispatchFenceService;

    @Autowired
    private com.aicostops.gateway.persistence.GatewayRequestMapper requestMapper;

    @Autowired
    private com.aicostops.gateway.request.RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void closeWinsLockRejectsBeforeDispatchAndLeavesNoFence() {
        var env = GatewayTestFixture.seed(jdbc, "fence-close", HMAC_KEY, rawKey());
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=? AND org_id=?",
                env.periodId(), env.orgId());

        var failure = requestService.authorizeAndFence(command(principal(env), env))
                .toFuture()
                .handle((result, ex) -> ex)
                .join();

        assertThat(failure).isInstanceOf(GatewayErrorException.class);
        assertThat(((GatewayErrorException) failure).code())
                .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE);

        // The closed period rejected the request before any durable dispatch
        // state existed: no request row, no route attempt, no financial fence.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_request WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM gateway_request
                WHERE org_id=? AND state='DISPATCH_INTENT'
                """, Integer.class, env.orgId())).isZero();
    }

    @Test
    void fenceRejectsClosedPeriodLeavingRequestValidatedAndAttemptPlanned() {
        var env = GatewayTestFixture.seed(jdbc, "fence-lock", HMAC_KEY, rawKey());
        var idemDigest = identityService.idempotencyKeyDigest("fence-lock-key");
        var fingerprint = identityService.requestFingerprint(
                "{\"model\":\"default-chat\"}".getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new com.aicostops.gateway.persistence.GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject("""
                SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, env.orgId());
        requestMapper.insertRouteAttempt(
                new com.aicostops.gateway.persistence.GatewayRequestMapper.RouteAttemptInsert(
                        env.orgId(), requestId, identityService.newRouteDecisionId(),
                        env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);

        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=? AND org_id=?",
                env.periodId(), env.orgId());

        assertThatThrownBy(() -> dispatchFenceService.commitDispatchFence(
                env.orgId(), requestId, attemptId, env.periodId(), unbudgeted()))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        // The fence transaction rolled back: no DISPATCH_INTENT, no period id.
        assertThat(jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=?",
                String.class, requestId)).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject("SELECT billing_period_id FROM gateway_request WHERE id=?",
                Long.class, requestId)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_route_attempt WHERE id=?",
                String.class, attemptId)).isEqualTo("PLANNED");
    }

    @Test
    void dispatchWinsCommitsFenceThatBlocksClose() {
        var env = GatewayTestFixture.seed(jdbc, "fence-dispatch", HMAC_KEY, rawKey());

        var result = requestService.authorizeAndFence(command(principal(env), env)).block();

        assertThat(result).isNotNull();
        assertThat(result.publicRequestId()).startsWith("gwr_");
        assertThat(result.routeDecisionId()).startsWith("grd_");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM gateway_request WHERE id=?
                """, String.class, result.requestId())).isEqualTo("DISPATCH_INTENT");
        assertThat(jdbc.queryForObject("""
                SELECT billing_period_id FROM gateway_request WHERE id=?
                """, Long.class, result.requestId())).isEqualTo(env.periodId());
        assertThat(jdbc.queryForObject("""
                SELECT status FROM gateway_route_attempt WHERE id=?
                """, String.class, result.routeAttemptId())).isEqualTo("DISPATCH_INTENT");
        assertThat(jdbc.queryForObject("""
                SELECT dispatch_intent_at IS NOT NULL FROM gateway_request WHERE id=?
                """, Integer.class, result.requestId())).isEqualTo(1);
    }

    @Test
    void fenceWithoutMatchingActiveReservationFails() {
        var env = GatewayTestFixture.seed(jdbc, "fence-nores", HMAC_KEY, rawKey());
        var idemDigest = identityService.idempotencyKeyDigest("fence-nores-key");
        var fingerprint = identityService.requestFingerprint(
                "{\"model\":\"default-chat\"}".getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new com.aicostops.gateway.persistence.GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject("""
                SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, env.orgId());
        requestMapper.insertRouteAttempt(
                new com.aicostops.gateway.persistence.GatewayRequestMapper.RouteAttemptInsert(
                        env.orgId(), requestId, identityService.newRouteDecisionId(),
                        env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);
        // Simulate a budget-controlled admission whose reservation was never
        // (or no longer) durably ACTIVE: the fence must fail instead of
        // dispatching an unbudgeted Provider call.
        var phantom = new com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult(
                com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome.RESERVED,
                999999L, env.budgetId(), new java.math.BigDecimal("31.94880000"));

        assertThatThrownBy(() -> dispatchFenceService.commitDispatchFence(
                env.orgId(), requestId, attemptId, env.periodId(), phantom))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        assertThat(jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=?",
                String.class, requestId)).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_request WHERE id=? AND state='DISPATCH_INTENT'",
                Integer.class, requestId)).isZero();
    }

    @Test
    void nonPlannedRouteAttemptFailsFenceAndRollsBackRequestTransition() {
        var env = GatewayTestFixture.seed(jdbc, "fence-attempt", HMAC_KEY, rawKey());
        var idemDigest = identityService.idempotencyKeyDigest("fence-attempt-key");
        var fingerprint = identityService.requestFingerprint(
                "{\"model\":\"default-chat\"}".getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new com.aicostops.gateway.persistence.GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject("""
                SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, env.orgId());
        requestMapper.insertRouteAttempt(
                new com.aicostops.gateway.persistence.GatewayRequestMapper.RouteAttemptInsert(
                        env.orgId(), requestId, identityService.newRouteDecisionId(),
                        env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);

        // Simulate a concurrent transition that moved the attempt out of
        // PLANNED before this fence commits: the fence must fail instead of
        // committing a half-fence with Provider I/O already legal.
        jdbc.update("UPDATE gateway_route_attempt SET status='BILLABLE_POSSIBLE' WHERE id=?",
                attemptId);

        assertThatThrownBy(() -> dispatchFenceService.commitDispatchFence(
                env.orgId(), requestId, attemptId, env.periodId(), unbudgeted()))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        // The whole fence rolled back: the request never reached
        // DISPATCH_INTENT, so no Provider I/O was ever legal for this attempt.
        assertThat(jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=?",
                String.class, requestId)).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject("SELECT billing_period_id FROM gateway_request WHERE id=?",
                Long.class, requestId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_request WHERE id=? AND state='DISPATCH_INTENT'",
                Integer.class, requestId)).isZero();
    }

    private GatewayPrincipal principal(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
    }

    private AuthorizeCommand command(GatewayPrincipal principal, SeededEnv env) {
        return new AuthorizeCommand(principal, env.modelId(),
                "{\"model\":\"default-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                        .getBytes(StandardCharsets.UTF_8),
                "dispatch-fence-key", 8192);
    }

    private static com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult unbudgeted() {
        return new com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult(
                com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome.UNBUDGETED,
                -1L, -1L, null);
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}