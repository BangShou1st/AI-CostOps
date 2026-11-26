package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aicostops.gateway.rate-limit-capacity=150",
        "spring.data.redis.timeout=300ms"
})
@Tag("integration")
class GatewayIdempotencyFastPathIntegrationTest extends GatewayMySqlContainerSupport {
    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String RAW_KEY = "aic_0123456789ab_" + "A".repeat(43);
    private static final String IDEM_KEY = "hc04-shared-identity";
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static HttpServer upstream;

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RequestIdentityService identity;
    @Autowired
    private GatewayRequestService service;
    @MockitoSpyBean
    private GatewayRequestMapper mapper;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            try (var request = exchange.getRequestBody()) {
                request.readAllBytes();
            }
            byte[] response = """
                    {"id":"chatcmpl-hc04","object":"chat.completion","created":1788000200,
                     "model":"test-chat-model","choices":[{"index":0,"finish_reason":"stop",
                       "message":{"role":"assistant","content":"hi"}}],
                     "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void oneInsertAndOneDispatchAcrossOneHundredConcurrentHttpRequests() throws Exception {
        UPSTREAM_CALLS.set(0);
        var env = seed("hc04-idem-http");
        var web = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(100);
        var requests = new ArrayList<Future<Integer>>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                requests.add(callers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post(web, body(env, "hi")).expectBody().returnResult().getStatus().value();
                }));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(allReady).isTrue();
            var statuses = new ArrayList<Integer>();
            for (var request : requests) {
                statuses.add(request.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        }
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        verify(mapper, times(1)).insertRequest(any());
        System.out.println("HC-04 same-key HTTP requests: 1 x 200, 99 x 409, 1 MySQL request INSERT");
        assertThat(countRequests(env)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_reservation WHERE org_id=?",
                Long.class, env.orgId())).isEqualTo(1L);
        var requestId = jdbc.queryForObject("SELECT id FROM gateway_request WHERE org_id=?",
                Long.class, env.orgId());
        assertThat(redis.opsForValue().get(redisKey(env))).isEqualTo(requestId.toString());

        post(web, body(env, "different")).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error.code").isEqualTo("GATEWAY_IDEMPOTENCY_CONFLICT");
        verify(mapper, times(1)).insertRequest(any());
    }

    @Test
    void abandonedRedisMarkerFallsBackToTheMysqlUniqueIdentity() {
        var env = seed("hc04-idem-abandoned");
        redis.opsForValue().set(redisKey(env), "abandoned-owner", Duration.ofDays(1));
        var first = service.authorizeForRouting(command(env)).block();
        var replay = service.authorizeForRouting(command(env)).block();
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(countRequests(env)).isEqualTo(1L);
        verify(mapper, times(1)).insertRequest(any());
    }

    @Test
    void redisOutageStillConvergesOnOneMysqlIdentity() throws Exception {
        var env = seed("hc04-idem-outage");
        assertThat(REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL").getStdout())
                .contains("OK");
        try {
            var first = service.authorizeForRouting(command(env)).block();
            var replay = service.authorizeForRouting(command(env)).block();
            assertThat(replay.requestId()).isEqualTo(first.requestId());
            assertThat(countRequests(env)).isEqualTo(1L);
            verify(mapper, times(1)).insertRequest(any());
        } finally {
            REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
        }
    }

    private SeededEnv seed(String suffix) {
        var url = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1";
        return GatewayTestFixture.seed(jdbc, suffix, HMAC_KEY, RAW_KEY,
                GatewayTestFixture.TEST_KEK, "sk-test-secret", url);
    }

    private WebTestClient.ResponseSpec post(WebTestClient web, String body) {
        return web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(RAW_KEY))
                .header("Idempotency-Key", IDEM_KEY)
                .bodyValue(body).exchange();
    }

    private static String body(SeededEnv env, String content) {
        return """
                {"model":"%s","messages":[{"role":"user","content":"%s"}]}
                """.formatted(env.modelKey(), content);
    }

    private GatewayRequestService.AuthorizeCommand command(SeededEnv env) {
        var principal = new GatewayPrincipal(env.credentialId(), env.orgId(), env.projectId(),
                "SERVICE", null, env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
        return new GatewayRequestService.AuthorizeCommand(principal, env.modelId(),
                body(env, "hi").getBytes(StandardCharsets.UTF_8), IDEM_KEY, 8192);
    }

    private String redisKey(SeededEnv env) {
        return GatewayIdempotencyHint.key(env.credentialId(), identity.idempotencyKeyDigest(IDEM_KEY));
    }

    private long countRequests(SeededEnv env) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_request WHERE org_id=?",
                Long.class, env.orgId());
    }
}
