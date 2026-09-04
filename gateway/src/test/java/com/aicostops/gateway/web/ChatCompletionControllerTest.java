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
import org.springframework.boot.test.web.server.LocalServerPort;
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
    private static final String MOCK_RESPONSE_WITHOUT_USAGE = """
            {"id":"chatcmpl_mockct_no_usage","object":"chat.completion","created":1788000101,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"Hello without usage"},"finish_reason":"stop"}]}
            """;

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static volatile String upstreamAuthHeader;
    private static volatile boolean omitUsage;

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private SeededEnv env;

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            upstreamAuthHeader = exchange.getRequestHeaders().getFirst("api-key");
            // Drain the request body before responding: leaving a 1 MiB body
            // unread while closing races the client's upload with a TCP RST.
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            var bytes = (omitUsage ? MOCK_RESPONSE_WITHOUT_USAGE : MOCK_RESPONSE)
                    .getBytes(StandardCharsets.UTF_8);
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
        omitUsage = false;
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
        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_usage_fact WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("FINAL");
    }

    @Test
    void successfulProviderResponseWithoutUsagePersistsUnknownWithoutZeroDimensions() {
        omitUsage = true;

        web.post().uri("/v1/chat/completions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-no-usage")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.usage").doesNotExist();

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_usage_fact WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_dimension WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("TRANSPORT_COMPLETED");
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
    void requiredBudgetCredentialReservesThenDispatches() {
        jdbc.update("UPDATE gateway_credential SET budget_enforcement_mode='REQUIRED' WHERE id=?",
                env.credentialId());

        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-5")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk();

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND status='ACTIVE'",
                Integer.class, env.orgId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("TRANSPORT_COMPLETED");
    }

    @Test
    void requiredBudgetCredentialWithoutBudgetFailsClosedBeforeProviderDispatch() {
        jdbc.update("UPDATE gateway_credential SET budget_enforcement_mode='REQUIRED' WHERE id=?",
                env.credentialId());
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget WHERE org_id=?", env.orgId());

        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-5-nobudget")
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
        web.post().uri("/v1/chat/completions")
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

    @Test
    void chunkedSmallJsonReachesBusinessPath() throws Exception {
        var status = postChunked(body(env), "ctrl-idem-chunked-1", null);

        assertThat(status).isEqualTo(200);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @Test
    void chunkedOversizeBodyReturns413WithoutProviderDispatch() throws Exception {
        var oversized = "{\"model\":\"" + env.modelKey() + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + "x".repeat(2_000_000) + "\"}]}";
        var result = postChunkedWithBody(oversized, "ctrl-idem-chunked-2", null);

        assertThat(result.statusCode()).isEqualTo(413);
        assertThat(result.body()).contains("GATEWAY_REQUEST_TOO_LARGE");
        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void bodyAtExactlyMaxBytesIsAccepted() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-bound-1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(sizedBody(env, 1_048_576))
                .exchange()
                .expectStatus().isOk();

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    @Test
    void bodyAtMaxPlusOneByteIsRejected413() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-bound-2")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(sizedBody(env, 1_048_577))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_TOO_LARGE");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void gzipContentEncodingIsRejected400WithoutProviderDispatch() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-enc-1")
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_INVALID");

        assertThat(UPSTREAM_CALLS.get()).isZero();
    }

    @Test
    void identityContentEncodingIsAccepted() {
        web.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "ctrl-idem-enc-2")
                .header(HttpHeaders.CONTENT_ENCODING, "identity")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk();

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    /** Sends a request with unknown Content-Length (chunked transfer encoding). */
    private int postChunked(String json, String idempotencyKey, String contentEncoding)
            throws Exception {
        return postChunkedWithBody(json, idempotencyKey, contentEncoding).statusCode();
    }

    private java.net.http.HttpResponse<String> postChunkedWithBody(
            String json, String idempotencyKey, String contentEncoding) throws Exception {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        var chunks = java.util.List.of(
                java.util.Arrays.copyOfRange(bytes, 0, bytes.length / 2),
                java.util.Arrays.copyOfRange(bytes, bytes.length / 2, bytes.length));
        var requestBuilder = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", idempotencyKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArrays(chunks));
        if (contentEncoding != null) {
            requestBuilder.header(HttpHeaders.CONTENT_ENCODING, contentEncoding);
        }
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        return client.send(requestBuilder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /** Builds a valid JSON body of exactly {@code totalBytes} UTF-8 bytes. */
    private static String sizedBody(SeededEnv env, int totalBytes) {
        var template = body(env);
        var marker = "\"content\":\"hi\"";
        var padding = totalBytes - template.length() + "hi".length();
        assertThat(padding).isGreaterThan(0);
        return template.replace(marker, "\"content\":\"" + "x".repeat(padding) + "\"");
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
