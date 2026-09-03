package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M12 TX1 on real MySQL: lock OPEN BillingPeriod, resolve exact/ORG Budget in
 * the pricing currency, lock the Budget, observe Total/Actual/Committed plus
 * effective holds under the same lock, then insert ACTIVE + RESERVED or reject
 * to REJECTED_BUDGET. OPTIONAL without a matching Budget proceeds unbudgeted.
 */
@SpringBootTest
@Tag("integration")
class BudgetReservationServiceIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private BudgetReservationService reservationService;

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
    void requiredReservesAgainstExactBudget() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-exact", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "tx1-exact-key");

        var result = reservationService.admit(command(
                required(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block();

        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(result.budgetId()).isEqualTo(env.budgetId());
        // input 1_048_576 * 30/1M + output 8192 * 60/1M = 31.9488
        assertThat(result.reservedAmount()).isEqualByComparingTo("31.94880000");
        assertThat(stateOf(ids.requestId())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject(
                "SELECT billing_period_id FROM gateway_request WHERE id=?",
                Long.class, ids.requestId())).isEqualTo(env.periodId());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?",
                String.class, result.reservationId())).isEqualTo("ACTIVE");
    }

    @Test
    void sameRouteAttemptReplaysWithoutSecondHold() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-replay", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "tx1-replay-key");

        var first = reservationService.admit(command(
                required(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block();
        var second = reservationService.admit(command(
                required(env), env, ids.requestId(), ids.attemptId(), "USD", 8192,
                first.budgetId())).block();

        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=?",
                Integer.class, env.orgId())).isOne();
    }

    @Test
    void requiredWithoutMatchingBudgetRejects() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-nobudget", HMAC_KEY, rawKey());
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget WHERE org_id=?", env.orgId());
        var ids = insertValidatedRequest(env, "tx1-nobudget-key");

        assertThatThrownBy(() -> reservationService.admit(command(
                required(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block())
                .satisfies(ex -> assertThat(rootError(ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED));

        assertThat(stateOf(ids.requestId())).isEqualTo("REJECTED_BUDGET");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
    }

    @Test
    void optionalWithoutMatchingBudgetProceedsUnbudgeted() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-unbudgeted", HMAC_KEY, rawKey());
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget WHERE org_id=?", env.orgId());
        var ids = insertValidatedRequest(env, "tx1-unbudgeted-key");

        var result = reservationService.admit(command(
                optional(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block();

        assertThat(result.outcome()).isEqualTo(AdmissionOutcome.UNBUDGETED);
        assertThat(stateOf(ids.requestId())).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
    }

    @Test
    void optionalWithExhaustedBudgetStillRejects() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-exhausted", HMAC_KEY, rawKey());
        // Exhaust the 100 Budget: actual 99 leaves 1, below the 31.94 bound.
        jdbc.update("UPDATE budget SET actual_amount='99.00000000' WHERE id=?", env.budgetId());
        var ids = insertValidatedRequest(env, "tx1-exhausted-key");

        assertThatThrownBy(() -> reservationService.admit(command(
                optional(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block())
                .satisfies(ex -> assertThat(rootError(ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED));

        assertThat(stateOf(ids.requestId())).isEqualTo("REJECTED_BUDGET");
    }

    @Test
    void differentCurrencyBudgetIsNoMatchingBudget() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-fx", HMAC_KEY, rawKey());
        // The seeded Budget is USD; a EUR-priced request must not touch it.
        // Switch the Budget to EUR to prove currency-exact matching: the USD
        // request then has no matching Budget.
        jdbc.update("UPDATE budget SET currency='EUR' WHERE id=?", env.budgetId());
        var ids = insertValidatedRequest(env, "tx1-fx-key");

        assertThatThrownBy(() -> reservationService.admit(command(
                required(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block())
                .satisfies(ex -> assertThat(rootError(ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
    }

    @Test
    void orgFallbackBudgetIsUsedWhenNoExactScopeBudget() {
        var env = GatewayTestFixture.seed(jdbc, "tx1-orgfb", HMAC_KEY, rawKey());
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget WHERE org_id=?", env.orgId());
        jdbc.update("""
                INSERT INTO budget(
                  org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?, 'ORG',?,'USD','100.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, env.orgId(), env.periodId(), env.orgId());
        var orgBudgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var ids = insertValidatedRequest(env, "tx1-orgfb-key");

        var result = reservationService.admit(command(
                required(env), env, ids.requestId(), ids.attemptId(), "USD", 8192, -1L)).block();

        assertThat(result.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(result.budgetId()).isEqualTo(orgBudgetId);
    }

    private RequestIds insertValidatedRequest(SeededEnv env, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"default-chat-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
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

    private String stateOf(long requestId) {
        return jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=?",
                String.class, requestId);
    }

    private AdmissionCommand command(GatewayPrincipal principal, SeededEnv env,
            long requestId, long attemptId, String currency, long maxTokens, long expectedBudgetId) {
        return new AdmissionCommand(principal, requestId, attemptId, env.periodId(),
                env.pricingVersionId(), currency, maxTokens, expectedBudgetId);
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

    private static GatewayErrorException rootError(Throwable ex) {
        for (var current = ex; current != null; current = current.getCause()) {
            if (current instanceof GatewayErrorException gatewayError) {
                return gatewayError;
            }
        }
        throw new AssertionError("No GatewayErrorException in chain: " + ex);
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private record RequestIds(long requestId, long attemptId) {
    }
}
