package com.aicostops.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * AIC-099 atomic Redis rate limiting over real Redis + real MySQL: burst and
 * refill behavior, a concurrent acquisition race limited to capacity, closed
 * (fail-closed) behavior when Redis is unreachable, and the end-to-end 429
 * {@code GATEWAY_RATE_LIMITED} edge before any Provider dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class RedisTokenBucketRateLimiterIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String OK_RESPONSE = """
            {"id":"chatcmpl_ratelimit","object":"chat.completion","created":1788000300,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisTokenBucketRateLimiter limiter;

    private SeededEnv env;

    @DynamicPropertySource
    static void registerRateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("aicostops.gateway.rate-limit-enabled", () -> true);
        registry.add("aicostops.gateway.rate-limit-capacity", () -> 2);
        registry.add("aicostops.gateway.rate-limit-refill-per-second", () -> 1);
    }

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            var bytes = OK_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        mockUpstream.start();
    }

    @AfterAll
    static void stopMock() {
        mockUpstream.stop(0);
    }

    @BeforeEach
    void seed() {
        UPSTREAM_CALLS.set(0);
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "rl-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void burstAllowsCapacityThenRejectsWithBoundedRetryAfter() {
        assertThat(limiter.tryAcquire(env.credentialId()).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(env.credentialId()).block().allowed()).isTrue();

        var third = limiter.tryAcquire(env.credentialId()).block();

        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterMillis()).isGreaterThan(0);
    }

    @Test
    void refillRestoresATokenOverTime() throws InterruptedException {
        assertThat(limiter.tryAcquire(env.credentialId()).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(env.credentialId()).block().allowed()).isTrue();
        assertThat(limiter.tryAcquire(env.credentialId()).block().allowed()).isFalse();

        Thread.sleep(1100);

        assertThat(limiter.tryAcquire(env.credentialId()).block().allowed()).isTrue();
    }

    @Test
    void concurrentRaceAllowsExactlyCapacityRequests() {
        var results = Flux.range(0, 10)
                .flatMap(ignored -> limiter.tryAcquire(env.credentialId()))
                .collectList()
                .block();

        assertThat(results).isNotNull();
        assertThat(results.stream().filter(GatewayRateLimiter.RateLimitResult::allowed).count())
                .isEqualTo(2);
        assertThat(results.stream().filter(r -> !r.allowed()).count()).isEqualTo(8);
    }

    @Test
    void redisUnavailableFailsClosedWithDependencyUnavailable() {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 1);
        var clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(java.time.Duration.ofMillis(1000))
                .build();
        var badFactory = new LettuceConnectionFactory(config, clientConfig);
        badFactory.afterPropertiesSet();
        var badTemplate = new ReactiveStringRedisTemplate(badFactory);
        var properties = new GatewayProperties();
        properties.setRateLimitEnabled(true);
        properties.setRateLimitCapacity(2);
        properties.setRateLimitRefillPerSecond(1);
        var badLimiter = new RedisTokenBucketRateLimiter(badTemplate, properties, Clock.systemUTC());

        StepVerifier.create(badLimiter.tryAcquire(99L))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(GatewayErrorException.class);
                    assertThat(((GatewayErrorException) ex).code())
                            .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE);
                })
                .verify(java.time.Duration.ofSeconds(15));
    }

    @Test
    void enabledLimiterRejectsOverCapacityBeforeProviderDispatch() {
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "rl-idem-1")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk();

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "rl-idem-2")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk();

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "rl-idem-3")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_RATE_LIMITED");

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(2);
    }

    private static String body(SeededEnv env) {
        return "{\"model\":\"" + env.modelKey()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}