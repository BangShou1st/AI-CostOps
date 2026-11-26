package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.request.RequestIdentityService;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-MySQL proof for the read-only periodic {@code budget.reserved_amount}
 * drift check: clean budgets report OK, synthetic drift increments the
 * Micrometer drift metric and logs, and NEVER auto-repairs {@code budget}.
 */
@SpringBootTest
@Tag("integration")
class BudgetReservedDriftCheckIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private BudgetReservedDriftCheckService driftCheckService;

    @Autowired
    private BudgetReservationService reservationService;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private RequestIdentityService identityService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void detectsAndCountsDriftWithoutAutoRepairing() {
        var env = GatewayTestFixture.seed(jdbc, "drift-check-" + System.nanoTime(), HMAC_KEY, rawKey());
        var idemDigest = identityService.idempotencyKeyDigest("drift-check-req");
        var fingerprint = identityService.requestFingerprint(
                "{\"model\":\"default-chat\"}".getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject(
                "SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, env.orgId());
        requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                env.orgId(), requestId, identityService.newRouteDecisionId(),
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1",
                Long.class, requestId);

        var principal = new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
        var admission = reservationService.admit(new AdmissionCommand(
                principal, requestId, attemptId, env.periodId(), env.pricingVersionId(),
                "USD", 8192L, -1L)).block();
        assertThat(admission.outcome()).isEqualTo(AdmissionOutcome.RESERVED);

        var okCountBefore = counterValue("gateway_budget_reserved_drift_check_total", "outcome", "OK");
        var cleanDrifts = driftCheckService.inspectReservedDrift();
        assertThat(cleanDrifts).isEmpty();
        assertThat(counterValue("gateway_budget_reserved_drift_check_total", "outcome", "OK"))
                .isEqualTo(okCountBefore + 1.0);

        // Inject synthetic drift directly in MySQL: check must detect and count it,
        // and MUST NOT auto-repair budget.reserved_amount.
        var driftedAmount = new BigDecimal("36.94880000");
        jdbc.update("UPDATE budget SET reserved_amount=? WHERE id=?", driftedAmount, env.budgetId());

        var driftCountBefore = counterValue("gateway_budget_reserved_drift_total");
        var driftCheckBefore = counterValue("gateway_budget_reserved_drift_check_total", "outcome", "DRIFT");
        var drifts = driftCheckService.inspectReservedDrift();

        assertThat(drifts).hasSize(1);
        var drift = drifts.getFirst();
        assertThat(drift.orgId()).isEqualTo(env.orgId());
        assertThat(drift.budgetId()).isEqualTo(env.budgetId());
        assertThat(drift.budgetReservedAmount()).isEqualByComparingTo("36.94880000");
        assertThat(drift.expectedReservedAmount()).isEqualByComparingTo("31.94880000");
        assertThat(counterValue("gateway_budget_reserved_drift_total"))
                .isEqualTo(driftCountBefore + 1.0);
        assertThat(counterValue("gateway_budget_reserved_drift_check_total", "outcome", "DRIFT"))
                .isEqualTo(driftCheckBefore + 1.0);
        assertThat(jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?",
                BigDecimal.class, env.budgetId())).isEqualByComparingTo("36.94880000");
    }

    private double counterValue(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
