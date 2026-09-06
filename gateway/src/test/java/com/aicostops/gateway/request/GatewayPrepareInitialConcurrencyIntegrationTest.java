package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.request.GatewayRequestService.AuthorizeCommand;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M16 B01 room-scale proof: concurrent identical {@code prepareInitial} calls
 * converge on one durable request and one route attempt. A follower that loses
 * the attempt race must observe the in-progress identity (409), never mark the
 * shared request terminal (403 + FAILED_PRE_DISPATCH).
 */
@SpringBootTest
@Tag("integration")
class GatewayPrepareInitialConcurrencyIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private GatewayRequestOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void concurrentIdenticalPrepareConvergesWithoutTerminalMarking() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "prep-conc-" + System.nanoTime(), HMAC_KEY, rawKey());
        var principal = principal(env);
        var body = "{\"model\":\"default-chat\"}".getBytes(StandardCharsets.UTF_8);

        int threads = 20;
        var prepared = new AtomicInteger();
        var inProgress = new AtomicInteger();
        var forbidden = new AtomicInteger();
        var otherFailures = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(1);
        var tasks = new java.util.concurrent.Future[threads];
        for (int i = 0; i < threads; i++) {
            tasks[i] = executor.submit(() -> {
                latch.await();
                try {
                    orchestrator.prepareInitial(
                            new AuthorizeCommand(principal, env.modelId(), body, "prep-idem-key", 8192),
                            false).block();
                    prepared.incrementAndGet();
                    return null;
                } catch (GatewayErrorException ex) {
                    if (ex.code() == GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS) {
                        inProgress.incrementAndGet();
                    } else if (ex.code() == GatewayErrorCode.GATEWAY_FORBIDDEN) {
                        forbidden.incrementAndGet();
                    } else {
                        otherFailures.incrementAndGet();
                    }
                    return null;
                }
            });
        }
        latch.countDown();
        for (var task : tasks) {
            task.get();
        }
        executor.shutdown();

        assertThat(forbidden.get()).as("followers must never see FORBIDDEN").isZero();
        assertThat(otherFailures.get()).as("unexpected failures").isZero();
        assertThat(prepared.get() + inProgress.get()).isEqualTo(threads);
        assertThat(countRequests(env)).isEqualTo(1);
        assertThat(countAttempts(env)).isEqualTo(1);
        assertThat(requestState(env)).isIn("VALIDATED", "RESERVED", "DISPATCH_INTENT");
    }

    private long countRequests(SeededEnv env) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_request WHERE org_id=?", Long.class, env.orgId());
    }

    private long countAttempts(SeededEnv env) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_route_attempt WHERE org_id=?", Long.class, env.orgId());
    }

    private String requestState(SeededEnv env) {
        return jdbc.queryForObject(
                "SELECT state FROM gateway_request WHERE org_id=? LIMIT 1", String.class, env.orgId());
    }

    private GatewayPrincipal principal(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
