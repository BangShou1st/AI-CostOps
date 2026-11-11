package com.aicostops.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import com.aicostops.gateway.auth.GatewayBearerWebFilter;
import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.config.GatewayResourceLimiter;
import com.aicostops.gateway.quota.GatewayQuotaLimiter;
import com.aicostops.gateway.ratelimit.GatewayRateLimiter;
import com.aicostops.gateway.request.GatewayRequestOrchestrator;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;

/**
 * B04 RED regression: an admitted SSE stream must hold its stream permit for
 * the whole upstream lifetime. The outer controller Mono completes when the
 * SSE response headers commit; releasing the permit there frees the ceiling
 * slot while the upstream call still occupies it, so ceiling+1 is wrongly
 * admitted. Deterministic hold/release uses latches, never sleeps as proof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class StreamPermitCeilingIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static volatile CountDownLatch upstreamEntered = new CountDownLatch(1);
    private static volatile CountDownLatch upstreamRelease = new CountDownLatch(1);

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayResourceLimiter limiter;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Autowired
    private ChatCompletionController controller;

    @MockitoSpyBean
    private GatewayRateLimiter rateLimiter;

    @MockitoSpyBean
    private GatewayQuotaLimiter quotaLimiter;

    @MockitoSpyBean
    private GatewayRequestOrchestrator orchestrator;

    private SeededEnv env;

    @DynamicPropertySource
    static void ceilingOfTwo(DynamicPropertyRegistry registry) {
        registry.add("aicostops.gateway.max-active-streams", () -> 2);
        registry.add("aicostops.gateway.header-timeout-ms", () -> 30000);
        registry.add("aicostops.gateway.stream-idle-timeout-ms", () -> 30000);
    }

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            // Headers first so the Gateway commits the SSE stream, then hold
            // the body open until the test releases the barrier.
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            try {
                upstreamEntered.countDown();
                if (!upstreamRelease.await(30, TimeUnit.SECONDS)) {
                    return;
                }
                var frames = ("data: {\"id\":\"chatcmpl_hold\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1788000200,\"model\":\"mimo-v2.5-pro\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                out.write(frames);
                out.flush();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Client may already have gone away.
                }
            }
            exchange.close();
        });
        mockUpstream.setExecutor(Executors.newCachedThreadPool());
        mockUpstream.start();
    }

    @AfterAll
    static void stopMock() {
        mockUpstream.stop(0);
    }

    @BeforeEach
    void seed() {
        upstreamEntered = new CountDownLatch(1);
        upstreamRelease = new CountDownLatch(1);
        UPSTREAM_CALLS.set(0);
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "permit-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        upstreamRelease.countDown();
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void heldStreamsOccupyPermitsAndOverCeilingIsRejectedWithoutDispatch() throws Exception {
        assertThat(limiter.maxActiveStreams()).isEqualTo(2);

        var first = consumeStreamAsync("permit-hold-1");
        assertThat(upstreamEntered.await(20, TimeUnit.SECONDS)).isTrue();
        awaitActiveStreams(1);

        var second = consumeStreamAsync("permit-hold-2");
        awaitUpstreamCalls(2);
        awaitActiveStreams(2);

        // Ceiling occupied: the third stream must be bounded 429 with zero
        // additional Provider work (rejected before dispatch).
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "permit-over-1")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_RATE_LIMITED");
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(2);

        // Release: both holders complete normally with exactly one [DONE].
        upstreamRelease.countDown();
        assertThat(first.get(20, TimeUnit.SECONDS)).contains("data: [DONE]");
        assertThat(second.get(20, TimeUnit.SECONDS)).contains("data: [DONE]");
        awaitActiveStreams(0);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(2);
    }

    @Test
    void streamPermitIsReleasedWhenRequestFailsBeforeStreamingBody() {
        // Force the exact route circuit open in Redis (the same state a real
        // provider outage produces). The stream request acquires a permit,
        // then fails at routing before invokeStream returns a body; the permit
        // must still be returned instead of leaking one ceiling slot.
        var circuitKey = "aicostops:gateway:circuit:v1:" + env.orgId() + ":"
                + env.providerAccountId() + ":" + env.providerModelId();
        redis.opsForValue().set(circuitKey, "OPEN|0|" + System.currentTimeMillis())
                .block(Duration.ofSeconds(5));
        try {
            web.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                    .header("Idempotency-Key", "permit-pre-stream-failure-1")
                    .bodyValue(body(env))
                    .exchange()
                    .expectStatus().isEqualTo(403)
                    .expectBody()
                    .jsonPath("$.error.code").isEqualTo("GATEWAY_FORBIDDEN");
            awaitActiveStreams(0);
            assertThat(UPSTREAM_CALLS.get()).isZero();
        } finally {
            redis.delete(circuitKey).block(Duration.ofSeconds(5));
        }
    }

    @Test
    void cancelWhileRateLimitPendingReleasesPermit() throws Exception {
        assertPreStreamCancelReleasesPermit(AdmissionStage.RATE_LIMIT);
    }

    @Test
    void cancelWhileQuotaPendingReleasesPermit() throws Exception {
        assertPreStreamCancelReleasesPermit(AdmissionStage.QUOTA);
    }

    @Test
    void cancelWhileAdmissionPendingReleasesPermit() throws Exception {
        assertPreStreamCancelReleasesPermit(AdmissionStage.ADMISSION);
    }

    /**
     * The permit is acquired before rate/quota/admission. A client that cancels
     * while one of those stages is still pending only produces a Reactor CANCEL
     * on the outer Mono (never an onError), so the outer chain must return the
     * permit when the SSE body never came into existence. The stage is stalled
     * with a controllable {@code Mono.never()} instead of a sleep, and the
     * subscription is cancelled by the test itself, so the direction is
     * deterministic: RED before the ownership fix, GREEN after it.
     */
    private void assertPreStreamCancelReleasesPermit(AdmissionStage stage) throws Exception {
        var stageEntered = new CountDownLatch(1);
        Mono<Object> pending = Mono.never().doOnSubscribe(ignored -> stageEntered.countDown());
        switch (stage) {
            case RATE_LIMIT -> doReturn(pending).when(rateLimiter).tryAcquire(anyLong());
            case QUOTA -> {
                doReturn(Mono.just(GatewayRateLimiter.RateLimitResult.allowed(1)))
                        .when(rateLimiter).tryAcquire(anyLong());
                doReturn(pending).when(quotaLimiter).tryAcquire(anyLong());
            }
            case ADMISSION -> {
                doReturn(Mono.just(GatewayRateLimiter.RateLimitResult.allowed(1)))
                        .when(rateLimiter).tryAcquire(anyLong());
                doReturn(Mono.just(GatewayQuotaLimiter.QuotaResult.allowed(1)))
                        .when(quotaLimiter).tryAcquire(anyLong());
                doReturn(pending).when(orchestrator).prepareInitial(any(), anyBoolean());
            }
        }

        var subscription = new AtomicReference<Subscription>();
        controller.createChatCompletion(streamExchange("permit-cancel-" + stage.name().toLowerCase()))
                .subscribe(new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription value) {
                        subscription.set(value);
                        value.request(1);
                    }
                });

        assertThat(stageEntered.await(20, TimeUnit.SECONDS))
                .describedAs("request must reach the %s stage", stage)
                .isTrue();
        assertThat(limiter.activeStreams())
                .describedAs("the stream permit must be held while admission is pending")
                .isEqualTo(1);

        subscription.get().cancel();

        awaitActiveStreams(0);
        assertThat(UPSTREAM_CALLS.get())
                .describedAs("a cancelled pre-stream request must never reach the Provider")
                .isZero();
    }

    private ServerWebExchange streamExchange(String idempotencyKey) {
        var request = MockServerHttpRequest.post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", idempotencyKey)
                .body(Mono.just(DefaultDataBufferFactory.sharedInstance
                        .wrap(body(env).getBytes(StandardCharsets.UTF_8))));
        var exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayBearerWebFilter.PRINCIPAL_ATTRIBUTE, principal());
        return exchange;
    }

    private GatewayPrincipal principal() {
        return new GatewayPrincipal(env.credentialId(), env.orgId(), env.projectId(), "SERVICE",
                null, env.serviceIdentityId(), "PROJECT", env.projectId(), "OPTIONAL");
    }

    private enum AdmissionStage {
        RATE_LIMIT, QUOTA, ADMISSION
    }

    private java.util.concurrent.Future<String> consumeStreamAsync(String idempotencyKey) {
        var executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        });
        return executor.submit(() -> {
            try {
                var result = web.post().uri("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                        .header("Idempotency-Key", idempotencyKey)
                        .bodyValue(body(env))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(String.class)
                        .returnResult();
                return result.getResponseBody() == null ? "" : result.getResponseBody();
            } finally {
                executor.shutdown();
            }
        });
    }

    private void awaitActiveStreams(int expected) {
        var deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (limiter.activeStreams() == expected) {
                return;
            }
            sleep(50);
        }
        assertThat(limiter.activeStreams())
                .describedAs("active streams never reached %d", expected)
                .isEqualTo(expected);
    }

    private void awaitUpstreamCalls(int expected) {
        var deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (UPSTREAM_CALLS.get() >= expected) {
                return;
            }
            sleep(50);
        }
        assertThat(UPSTREAM_CALLS.get()).isGreaterThanOrEqualTo(expected);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String body(SeededEnv env) {
        return "{\"model\":\"" + env.modelKey()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
