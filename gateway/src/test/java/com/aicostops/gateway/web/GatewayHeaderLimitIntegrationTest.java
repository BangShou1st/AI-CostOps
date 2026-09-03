package com.aicostops.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Server-level request-header bound proof with a real Reactor Netty server:
 * the {@code server.max-http-request-header-size} limit (driven by
 * {@code AICOSTOPS_GATEWAY_MAX_HEADER_BYTES}) rejects oversized headers
 * before any business code runs, so no Provider I/O happens and no durable
 * request row is created. A normal header set still reaches the business path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class GatewayHeaderLimitIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String MOCK_RESPONSE = """
            {"id":"chatcmpl_hdr","object":"chat.completion","created":1788000100,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private SeededEnv env;

    @DynamicPropertySource
    static void registerHeaderLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("server.max-http-request-header-size", () -> "2KB");
    }

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
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
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "hdr-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void headerLimitPropertyIsWiredFromServerLevel() {
        assertThat(environment.getProperty("server.max-http-request-header-size"))
                .isEqualTo("2KB");
    }

    @Test
    void normalHeadersReachBusinessPath() throws Exception {
        var response = post(body(env), "hdr-idem-1", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_request WHERE org_id=? AND state='TRANSPORT_COMPLETED'",
                Integer.class, env.orgId())).isEqualTo(1);
    }

    @Test
    void oversizedHeaderIsRejectedAtServerLayerWithoutProviderDispatch() throws Exception {
        int status = -1;
        try {
            var response = post(body(env), "hdr-idem-2", "y".repeat(4096));
            status = response.statusCode();
        } catch (IOException | InterruptedException ex) {
            // The server may abort the connection instead of writing a status;
            // either way no business code ran (asserted below).
            status = -1;
        }

        // Either a 4xx rejection or a connection abort: never a business success.
        assertThat(status).isNotEqualTo(200);
        assertThat(UPSTREAM_CALLS.get()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_request WHERE org_id=?",
                Integer.class, env.orgId())).isZero();
    }

    private HttpResponse<String> post(String json, String idempotencyKey, String padding)
            throws Exception {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey())
                .header("Idempotency-Key", idempotencyKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (padding != null) {
            builder.header("X-Padding", padding);
        }
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(SeededEnv env) {
        return "{\"model\":\"" + env.modelKey()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
