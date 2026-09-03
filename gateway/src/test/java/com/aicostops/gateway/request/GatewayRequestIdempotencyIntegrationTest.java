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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AIC-096 idempotency on real MySQL: one idempotency identity converges to
 * one durable request and one route attempt; the same key with a different
 * raw body deterministically conflicts; after dispatch, replay never
 * re-dispatches.
 */
@SpringBootTest
@Tag("integration")
class GatewayRequestIdempotencyIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private GatewayRequestService requestService;

    @Autowired
    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void concurrentSameIdentityProducesOneRequestOneAttemptOneDispatch() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "idem-conc", HMAC_KEY, rawKey());
        var principal = principal(env);
        var command = command(principal, env, "{\"model\":\"default-chat\"}");

        int threads = 12;
        var successes = new AtomicInteger();
        var inProgress = new AtomicInteger();
        var otherFailures = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(1);
        var tasks = new java.util.concurrent.Future[20];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = executor.submit(() -> {
                latch.await();
                try {
                    requestService.authorizeAndFence(command).block();
                    successes.incrementAndGet();
                    return null;
                } catch (GatewayErrorException ex) {
                    if (ex.code() == GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS) {
                        inProgress.incrementAndGet();
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

        assertThat(successes.get()).isEqualTo(1);
        assertThat(inProgress.get()).isEqualTo(19);
        assertThat(otherFailures.get()).isZero();
        assertThat(countRequests(env)).isEqualTo(1);
        assertThat(countAttempts(env)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRawBodyDeterministicallyConflicts() {
        var env = GatewayTestFixture.seed(jdbc, "idem-conflict", HMAC_KEY, rawKey());
        var principal = principal(env);

        requestService.authorizeAndFence(command(principal, env, "{\"model\":\"default-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]}"))
                .block();

        var conflict = requestService.authorizeAndFence(command(principal, env,
                "{\"model\":\"default-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"b\"}]}"))
                .toFuture()
                .handle((result, ex) -> ex)
                .join();

        assertThat(conflict).isInstanceOf(GatewayErrorException.class);
        assertThat(((GatewayErrorException) conflict).code())
                .isEqualTo(GatewayErrorCode.GATEWAY_IDEMPOTENCY_CONFLICT);
        assertThat(countRequests(env)).isEqualTo(1);
        assertThat(countAttempts(env)).isEqualTo(1);
    }

    @Test
    void replayAfterDispatchIsInProgressNeverReDispatches() {
        var env = GatewayTestFixture.seed(jdbc, "idem-replay", HMAC_KEY, rawKey());
        var principal = principal(env);

        var first = requestService.authorizeAndFence(command(principal, env, "{\"model\":\"default-chat\"}"))
                .block();
        var second = requestService.authorizeAndFence(command(principal, env, "{\"model\":\"default-chat\"}"))
                .toFuture()
                .handle((result, ex) -> ex)
                .join();

        assertThat(first).isNotNull();
        assertThat(second).isInstanceOf(GatewayErrorException.class);
        assertThat(((GatewayErrorException) second).code())
                .isEqualTo(GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS);
        assertThat(countRequests(env)).isEqualTo(1);
        assertThat(countAttempts(env)).isEqualTo(1);
    }

    private long countRequests(SeededEnv env) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_request WHERE org_id=?",
                Long.class, env.orgId());
    }

    private long countAttempts(SeededEnv env) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt WHERE org_id=?",
                Long.class, env.orgId());
    }

    private GatewayPrincipal principal(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
    }

    private AuthorizeCommand command(GatewayPrincipal principal, SeededEnv env, String body) {
        return new AuthorizeCommand(principal, env.modelId(),
                body.getBytes(StandardCharsets.UTF_8), "idem-key-123", 8192);
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}