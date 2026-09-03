package com.aicostops.gateway.request;

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
import java.util.concurrent.Executors;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AIC-098 streaming lifecycle over real MySQL: a real client disconnect after
 * dispatch becomes {@code CANCELED_AFTER_DISPATCH} with the route attempt left
 * {@code BILLABLE_POSSIBLE}, and exactly one Provider request is ever issued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class StreamingLifecycleIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private SeededEnv env;

    @DynamicPropertySource
    static void registerTimeoutProperties(DynamicPropertyRegistry registry) {
        registry.add("aicostops.gateway.stream-idle-timeout-ms", () -> 2000);
        registry.add("aicostops.gateway.hard-timeout-ms", () -> 30_000);
        registry.add("aicostops.gateway.header-timeout-ms", () -> 3000);
    }

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            int index = 0;
            try {
                while (true) {
                    out.write(chunkFrame(index++).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    sleep(30);
                }
            } catch (IOException ex) {
                // Client (or the Gateway on cancel) closed the connection.
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Already closed.
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
        UPSTREAM_CALLS.set(0);
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "ls-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void clientCancelAfterPartialChunksMarksCanceledAfterDispatchAndBillablePossible()
            throws Exception {
        var response = sendStreamingRequest("lifecycle-cancel-1");
        assertThat(response.statusCode()).isEqualTo(200);

        // Read only a small prefix of the stream, then abandon the connection:
        // this is the frozen client-disconnect-after-dispatch case.
        var input = response.body();
        var buffer = new byte[512];
        var firstRead = input.read(buffer, 0, 128);
        assertThat(firstRead).isGreaterThan(0);
        input.close();

        awaitState("CANCELED_AFTER_DISPATCH");
        assertThat(attemptStatus()).isEqualTo("BILLABLE_POSSIBLE");
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT terminal_at IS NOT NULL FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Boolean.class, env.orgId())).isTrue();
    }

    @Test
    void replayAfterCancelIsInProgressConflictWithoutSecondProviderCall() throws Exception {
        var response = sendStreamingRequest("lifecycle-cancel-2");
        assertThat(response.statusCode()).isEqualTo(200);
        var input = response.body();
        var buffer = new byte[512];
        var firstRead = input.read(buffer, 0, 64);
        assertThat(firstRead).isGreaterThan(0);
        input.close();

        awaitState("CANCELED_AFTER_DISPATCH");

        // Same idempotency identity after a post-dispatch uncertain outcome:
        // no second Provider call, deterministic in-progress conflict.
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var replayRequest = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + rawKey())
                .header("Idempotency-Key", "lifecycle-cancel-2")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(env)))
                .build();
        var replay = client.send(replayRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(replay.statusCode()).isEqualTo(409);
        assertThat(replay.body()).contains("GATEWAY_REQUEST_IN_PROGRESS");
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
    }

    private HttpResponse<java.io.InputStream> sendStreamingRequest(String idempotencyKey)
            throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + rawKey())
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(env)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private void awaitState(String expected) {
        var deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(requestState())) {
                return;
            }
            sleep(100);
        }
        assertThat(requestState()).describedAs("request never reached %s", expected)
                .isEqualTo(expected);
    }

    private String requestState() {
        return jdbc.queryForObject(
                "SELECT state FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId());
    }

    private String attemptStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM gateway_route_attempt WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId());
    }

    private static String chunkFrame(int index) {
        return "data: {\"id\":\"chatcmpl_ls" + index + "\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1788000200,\"model\":\"mimo-v2.5-pro\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"t\"},\"finish_reason\":null}]}\n\n";
    }

    private static String body(SeededEnv env) {
        return "{\"model\":\"" + env.modelKey()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}