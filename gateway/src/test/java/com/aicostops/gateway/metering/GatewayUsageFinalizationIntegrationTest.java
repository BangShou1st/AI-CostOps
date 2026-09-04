package com.aicostops.gateway.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.persistence.GatewayUsageMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.persistence.GatewayUsageMapper;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;

/** Real MySQL coverage for append-only usage publication and transactionality. */
@SpringBootTest
@org.junit.jupiter.api.Tag("integration")
class GatewayUsageFinalizationIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-04T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayUsageFinalizationService finalization;

    @Autowired
    private GatewayUsageMapper usageMapper;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private GatewayUsageClassifier classifier;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BlockingIoScheduler blockingIo;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    private SeededEnv env;

    @DynamicPropertySource
    static void disableRuntimeLimiters(DynamicPropertyRegistry registry) {
        registry.add("aicostops.gateway.rate-limit-enabled", () -> false);
        registry.add("aicostops.gateway.quota-enabled", () -> false);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void finalizesUsageAndLifecycleAtomicallyWithFrozenRouteLineage() {
        var request = insertDispatchedRequest("final");

        var result = finalization.finalizeSuccess(request.requestId(), env.orgId(),
                request.attemptId(), GatewayUsageObservation.providerFinal(5, 3, null, OBSERVED_AT))
                .block();

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.FINAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=?",
                Integer.class, env.orgId())).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_usage_fact WHERE org_id=?",
                String.class, env.orgId())).isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("SELECT pricing_version_id FROM gateway_usage_fact WHERE org_id=?",
                Long.class, env.orgId())).isEqualTo(env.pricingVersionId());
        assertThat(jdbc.queryForObject("SELECT current_usage_fact_id FROM gateway_request WHERE id=?",
                Long.class, request.requestId())).isEqualTo(result.usageFactId());
        assertThat(jdbc.queryForObject("SELECT state FROM gateway_request WHERE id=?",
                String.class, request.requestId())).isEqualTo("TRANSPORT_COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_route_attempt WHERE id=?",
                String.class, request.attemptId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT GROUP_CONCAT(dimension_code ORDER BY dimension_code) "
                + "FROM gateway_usage_dimension WHERE usage_fact_id=?", String.class,
                result.usageFactId())).isEqualTo("INPUT_TOKEN,OUTPUT_TOKEN");
    }

    @Test
    void incompleteThenFinalAppendsRevisionAndNeverUpdatesOldFact() {
        var request = insertDispatchedRequest("revision");

        var incomplete = finalization.finalizeSuccess(request.requestId(), env.orgId(),
                request.attemptId(), GatewayUsageObservation.providerFinal(5, null, null, OBSERVED_AT))
                .block();
        var finalResult = finalization.finalizeSuccess(request.requestId(), env.orgId(),
                request.attemptId(), GatewayUsageObservation.providerFinal(5, 3, null, OBSERVED_AT))
                .block();

        assertThat(incomplete.status()).isEqualTo(GatewayUsageStatus.INCOMPLETE);
        assertThat(finalResult.status()).isEqualTo(GatewayUsageStatus.FINAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=?",
                Integer.class, env.orgId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sequence FROM gateway_usage_fact WHERE id=?",
                Integer.class, incomplete.usageFactId())).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM gateway_usage_fact WHERE id=?",
                String.class, incomplete.usageFactId())).isEqualTo("INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT supersedes_usage_fact_id FROM gateway_usage_fact WHERE id=?",
                Long.class, finalResult.usageFactId())).isEqualTo(incomplete.usageFactId());
        assertThat(jdbc.queryForObject("SELECT current_usage_fact_id FROM gateway_request WHERE id=?",
                Long.class, request.requestId())).isEqualTo(finalResult.usageFactId());
    }

    @Test
    void concurrentFinalPublicationConvergesToOneFact() {
        var request = insertDispatchedRequest("concurrent");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = CompletableFuture.supplyAsync(() -> publish(request), pool);
            var second = CompletableFuture.supplyAsync(() -> publish(request), pool);
            var one = first.join();
            var two = second.join();

            assertThat(one.usageFactId()).isEqualTo(two.usageFactId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=?",
                    Integer.class, env.orgId())).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureDuringPointerPublicationRollsBackFactAndDimensions() {
        var request = insertDispatchedRequest("rollback");
        var failingFinalization = new GatewayUsageFinalizationService(
                usageMapper, requestMapper, null, classifier, transactionManager, blockingIo,
                objectMapper, clock) {
            @Override
            protected void beforeLifecycleUpdate() {
                throw new IllegalStateException("m13 injected failure");
            }
        };
        assertThatThrownBy(() -> failingFinalization.finalizeSuccess(request.requestId(), env.orgId(),
                    request.attemptId(), GatewayUsageObservation.providerFinal(5, 3, null, OBSERVED_AT))
                    .block()).hasMessageContaining("m13 injected failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_dimension WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT current_usage_fact_id FROM gateway_request WHERE id=?",
                Object.class, request.requestId())).isNull();
    }

    private GatewayUsageFinalizationService.FinalizationResult publish(Request request) {
        return finalization.finalizeSuccess(request.requestId(), env.orgId(), request.attemptId(),
                GatewayUsageObservation.providerFinal(5, 3, null, OBSERVED_AT)).block();
    }

    private Request insertDispatchedRequest(String suffix) {
        env = GatewayTestFixture.seed(jdbc, "m13-final-" + suffix + System.nanoTime(), HMAC_KEY,
                rawKey());
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,logical_model_id,
                  api_surface,idempotency_key_digest,request_fingerprint,request_hmac_version,state,
                  billing_period_id,current_route_attempt_id,current_usage_fact_id,created_at,validated_at,
                  dispatch_intent_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'UPSTREAM_ACTIVE',?,NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, env.orgId(), "gwr_m13_" + suffix + System.nanoTime(), env.credentialId(),
                env.serviceIdentityId(), env.projectId(), env.projectId(), env.modelId(),
                digest(1), digest(2), env.periodId());
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(
                  org_id,request_id,attempt_no,route_decision_id,provider_account_id,provider_model_id,
                  pricing_version_id,status,created_at,dispatch_intent_at)
                VALUES (?,?,1,?,?,?,?,'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, env.orgId(), requestId, "grd_m13_" + suffix + System.nanoTime(),
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId());
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        return new Request(requestId, attemptId);
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private record Request(long requestId, long attemptId) {
    }
}
