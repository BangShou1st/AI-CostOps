package com.aicostops.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * AIC-100 recovery status API over real MySQL + mock upstream: owning
 * credential reads bounded status with null metering/settlement in M11, and
 * nonexistent or foreign-owned requests both surface the same privacy-
 * preserving 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class GatewayRequestStatusControllerTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String MOCK_RESPONSE = """
            {"id":"chatcmpl_status","object":"chat.completion","created":1788000400,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static HttpServer mockUpstream;

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    private SeededEnv env;
    private SeededEnv otherEnv;

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
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
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "sts-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        otherEnv = GatewayTestFixture.seed(jdbc, "sts-other-" + System.nanoTime(), HMAC_KEY,
                otherRawKey(), GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void owningCredentialReadsBoundedStatusAfterSuccessfulCompletion() {
        var post = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "sts-idem-1")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        var requestId = post.getResponseHeaders().getFirst("X-AI-CostOps-Request-Id");
        assertThat(requestId).startsWith("gwr_");

        web.get().uri("/v1/gateway/requests/{requestId}", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.requestId").isEqualTo(requestId)
                .jsonPath("$.requestState").isEqualTo("TRANSPORT_COMPLETED")
                .jsonPath("$.meteringStatus").isEmpty()
                .jsonPath("$.settlementStatus").isEmpty()
                .jsonPath("$.createdAt").exists()
                .jsonPath("$.updatedAt").exists();
    }

    @Test
    void foreignCredentialCannotReadRequestPrivacyPreserving404() {
        var post = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "sts-idem-2")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        var requestId = post.getResponseHeaders().getFirst("X-AI-CostOps-Request-Id");

        web.get().uri("/v1/gateway/requests/{requestId}", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearer(otherRawKey()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_NOT_FOUND");
    }

    @Test
    void nonexistentRequestIsPrivacyPreserving404() {
        web.get().uri("/v1/gateway/requests/gwr_00000000-0000-4000-8000-000000000000")
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_REQUEST_NOT_FOUND");
    }

    @Test
    void statusNeverExposesPromptOrSecrets() {
        var post = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "sts-idem-3")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        var requestId = post.getResponseHeaders().getFirst("X-AI-CostOps-Request-Id");

        var statusBody = web.get().uri("/v1/gateway/requests/{requestId}", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(statusBody).doesNotContain("hi", "sk-test-secret", "sts-idem-3");
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

    private static String otherRawKey() {
        return "aic_0123456789cd_" + "B".repeat(43);
    }
}