package com.aicostops.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * AIC-097 end-to-end HTTP surface with a controllable mock upstream: request
 * subset validation, durable dispatch, non-streaming completion, and the
 * frozen idempotency/budget/auth semantics over real MySQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class ChatCompletionControllerTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String MOCK_RESPONSE = """
            {"id":"chatcmpl_mockct","object":"chat.completion","created":1788000100,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"Hello from MiMo"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static volatile String upstreamAuthHeader;

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    private SeededEnv env;

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            upstreamAuthHeader = exchange.getRequestHeaders().getFirst("api-key");
            var bytes = MOCK_RESPONSE.getBytes(StandardCharsets.UTF_8);
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
        upstreamAuthHeader = null;
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "ctrl-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void nonStreamingRequestReturnsCompletionWithCorrelationAndState() {
        web.post().uri("/v1/chat/completions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-AI-CostOps-Request-Id")
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.object").isEqualTo("chat.completion")
                .jsonPath("$.choices[0].message.content").isEqualTo("Hello from MiMo")
                .jsonPath("$.choices[0].message.role").isEqualTo("assistant")
                .jsonPath("$.usage.total_tokens").isEqualTo(8);

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(upstreamAuthHeader).isEqualTo("sk-test-secret");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("TRANSPORT_COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_route_attempt WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("COMPLETED");
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_INVALID");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void unknownRequestFieldIsRejected() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-2")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"" + env.modelKey() + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"n\":2}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_INVALID");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void oversizeBodyReturns413BeforeProviderDispatch() {
        var oversized = "{\"model\":\"" + env.modelKey() + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "x".repeat(2_000_000) + "\"}]}";

        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-3")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(oversized)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_TOO_LARGE");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void invalidGatewayKeyReturns401Envelope() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer("aic_0123456789ab_" + "B".repeat(43)))
                .header("Idempotency-Key", "ctrl-idem-4")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_AUTH_INVALID");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void requiredBudgetCredentialFailsClosedBeforeProviderDispatch() {
        jdbc.update("UPDATE gateway_credential SET budget_enforcement_mode='REQUIRED' WHERE id=?",
                env.credentialId());

        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-5")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_BUDGET_EXHAUSTED");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void replayAfterSuccessfulDispatchIsInProgressNeverReDispatches() {
        var valid = web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-6")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);

        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-6")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isEqualTo(java.net.HttpURLConnection.HTTP_CONFLICT)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_RESPONSE_NOT_RETAINED");

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
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