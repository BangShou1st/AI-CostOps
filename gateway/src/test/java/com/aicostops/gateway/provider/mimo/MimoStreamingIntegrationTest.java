package com.aicostops.gateway.provider.mimo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * AIC-098 SSE transport over real MySQL + a controllable mock upstream:
 * multi-chunk success with exactly one {@code [DONE]}, bounded high-volume
 * streaming, Provider HTTP error before any chunk, connection reset after
 * partial chunks, stream idle timeout and hard-deadline timeout. Every
 * post-dispatch failure leaves the route attempt BILLABLE_POSSIBLE and never
 * issues a second Provider request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class MimoStreamingIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    enum Scenario {
        OK_3_CHUNKS, HIGH_VOLUME, ERROR_500, CHUNKS_THEN_IDLE, SLOW_STREAM, CLEAN_EOF_NO_DONE
    }

    private static HttpServer mockUpstream;
    private static ServerSocket resetServer;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static final AtomicInteger RESET_CALLS = new AtomicInteger();
    private static volatile Scenario scenario = Scenario.OK_3_CHUNKS;

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    private SeededEnv env;

    @DynamicPropertySource
    static void registerTimeoutProperties(DynamicPropertyRegistry registry) {
        registry.add("aicostops.gateway.stream-idle-timeout-ms", () -> 400);
        registry.add("aicostops.gateway.hard-timeout-ms", () -> 1200);
        registry.add("aicostops.gateway.header-timeout-ms", () -> 3000);
    }

    @BeforeAll
    static void startMock() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/", exchange -> {
            UPSTREAM_CALLS.incrementAndGet();
            if (scenario == Scenario.ERROR_500) {
                var body = "{\"error\":{\"message\":\"boom\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            try {
                switch (scenario) {
                    case OK_3_CHUNKS -> {
                        out.write(chunkFrame(1).getBytes(StandardCharsets.UTF_8));
                        out.write(chunkFrame(2).getBytes(StandardCharsets.UTF_8));
                        out.write(chunkFrame(3).getBytes(StandardCharsets.UTF_8));
                        out.write(usageFrame().getBytes(StandardCharsets.UTF_8));
                        out.write(DONE_FRAME.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    case HIGH_VOLUME -> {
                        for (int i = 0; i < 200; i++) {
                            out.write(chunkFrame(i).getBytes(StandardCharsets.UTF_8));
                        }
                        out.write(DONE_FRAME.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    case CHUNKS_THEN_IDLE -> {
                        out.write(chunkFrame(1).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        sleep(10_000);
                    }
                    case SLOW_STREAM -> {
                        for (int i = 0; i < 20; i++) {
                            out.write(chunkFrame(i).getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            sleep(100);
                        }
                    }
                    case CLEAN_EOF_NO_DONE -> {
                        // Clean EOF after partial chunks but without the
                        // terminal [DONE]: closing the exchange completes the
                        // chunked response normally at the transport level.
                        out.write(chunkFrame(1).getBytes(StandardCharsets.UTF_8));
                        out.write(chunkFrame(2).getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    default -> {
                        // Unreachable; ERROR_500 handled above.
                    }
                }
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Connection may already be closed by the client/Gateway.
                }
            }
            exchange.close();
        });
        mockUpstream.setExecutor(Executors.newCachedThreadPool());
        mockUpstream.start();

        // Raw socket upstream that sends partial SSE chunks and then closes with
        // SO_LINGER(0), producing a real TCP RST for the reset-after-partial case.
        resetServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var thread = new Thread(() -> {
            while (!resetServer.isClosed()) {
                try {
                    var socket = resetServer.accept();
                    handleRawReset(socket);
                } catch (IOException ex) {
                    break;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static void handleRawReset(Socket socket) {
        try {
            RESET_CALLS.incrementAndGet();
            socket.setSoTimeout(5000);
            var in = socket.getInputStream();
            var head = new ByteArrayOutputStream();
            var headText = "";
            while (!headText.contains("\r\n\r\n")) {
                int b = in.read();
                if (b == -1) {
                    return;
                }
                head.write(b);
                headText = head.toString(StandardCharsets.ISO_8859_1);
            }
            var requestHead = headText;
            var contentLength = 0;
            for (var headerLine : requestHead.split("\r\n")) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                }
            }
            long remaining = contentLength;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0 && in.read() == -1) {
                    return;
                }
                remaining -= skipped > 0 ? skipped : 1;
            }
            var out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(chunkResetFrame(1).getBytes(StandardCharsets.UTF_8));
            out.write(chunkResetFrame(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Let the 200 header and partial chunks reach the Gateway before
            // aborting, so the client observes a started stream being reset.
            sleep(150);
            // RST on close: the Gateway sees an aborted transport after partial chunks.
            socket.setSoLinger(true, 0);
            socket.close();
        } catch (Exception ignored) {
            // The client may already have observed the abort.
        }
    }

    @AfterAll
    static void stopMock() throws IOException {
        mockUpstream.stop(0);
        resetServer.close();
    }

    @BeforeEach
    void seed() {
        scenario = Scenario.OK_3_CHUNKS;
        UPSTREAM_CALLS.set(0);
        RESET_CALLS.set(0);
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "sse-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void sseStreamReturnsEveryChunkWithExactlyOneDoneAndTerminalState() {
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "stream-idem-1")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody()
                .consumeWith(result -> {
                    var text = new String(result.getResponseBody(), StandardCharsets.UTF_8);
                    assertThat(countOccurrences(text, "\"object\":\"chat.completion.chunk\"")).isEqualTo(3);
                    assertThat(text).contains("\"delta\":{\"content\":\"t1\"}")
                            .contains("\"delta\":{\"content\":\"t2\"}")
                            .contains("\"delta\":{\"content\":\"t3\"}");
                    assertThat(countOccurrences(text, "data: [DONE]")).isEqualTo(1);
                });

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(requestState()).isEqualTo("TRANSPORT_COMPLETED");
        assertThat(attemptStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_usage_fact WHERE org_id=? ORDER BY id DESC LIMIT 1",
                String.class, env.orgId())).isEqualTo("FINAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_dimension "
                + "WHERE org_id=?", Integer.class, env.orgId())).isEqualTo(2);
    }

    @Test
    void boundedHighVolumeStreamForwardsEveryChunkWithoutAggregation() {
        scenario = Scenario.HIGH_VOLUME;

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "stream-idem-2")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(result -> {
                    var text = new String(result.getResponseBody(), StandardCharsets.UTF_8);
                    assertThat(countOccurrences(text, "\"object\":\"chat.completion.chunk\"")).isEqualTo(200);
                    assertThat(countOccurrences(text, "data: [DONE]")).isEqualTo(1);
                });

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(requestState()).isEqualTo("TRANSPORT_COMPLETED");
    }

    @Test
    void providerHttpErrorBeforeAnyChunkReturnsJsonEnvelopeWithNoRetry() {
        scenario = Scenario.ERROR_500;

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                .header("Idempotency-Key", "stream-idem-3")
                .bodyValue(body(env))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("GATEWAY_UPSTREAM_FAILED");

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(requestState()).isEqualTo("FAILED_AFTER_DISPATCH");
        assertThat(attemptStatus()).isEqualTo("BILLABLE_POSSIBLE");
    }

    @Test
    void resetAfterPartialChunksLeavesFailedAfterDispatchAndBillablePossible() {
        // Point the seeded Provider at the raw RST upstream for this test.
        var resetUrl = "http://127.0.0.1:" + resetServer.getLocalPort() + "/v1";
        GatewayTestFixture.clean(jdbc);
        env = GatewayTestFixture.seed(jdbc, "sse-rst-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", resetUrl);

        consumeStreamIgnoringTermination("stream-idem-4");

        assertThat(RESET_CALLS.get()).isEqualTo(1);
        awaitState("FAILED_AFTER_DISPATCH");
        assertThat(attemptStatus()).isEqualTo("BILLABLE_POSSIBLE");
    }

    @Test
    void idleTimeoutMarksTimedOutAfterDispatchAndKeepsBillablePossible() {
        scenario = Scenario.CHUNKS_THEN_IDLE;

        consumeStreamIgnoringTermination("stream-idem-5");

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        awaitState("TIMED_OUT_AFTER_DISPATCH");
        assertThat(attemptStatus()).isEqualTo("BILLABLE_POSSIBLE");
    }

    @Test
    void hardDeadlineMarksTimedOutAfterDispatchOnSlowContinuousStream() {
        scenario = Scenario.SLOW_STREAM;

        consumeStreamIgnoringTermination("stream-idem-6");

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        awaitState("TIMED_OUT_AFTER_DISPATCH");
        assertThat(attemptStatus()).isEqualTo("BILLABLE_POSSIBLE");
    }

    @Test
    void cleanEofWithoutDoneIsFailedAfterDispatchWithNoSynthesizedDone() {
        scenario = Scenario.CLEAN_EOF_NO_DONE;

        var body = captureStreamBody("stream-idem-7");

        // The Gateway must not synthesize a terminal [DONE] the Provider
        // never sent: partial chunks may be forwarded, but no completion
        // signal, and the durable state is a post-dispatch failure with no
        // automatic retry (exactly one Provider call).
        assertThat(body).doesNotContain("data: [DONE]");
        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        awaitState("FAILED_AFTER_DISPATCH");
        assertThat(attemptStatus()).isEqualTo("BILLABLE_POSSIBLE");
    }

    private void consumeStreamIgnoringTermination(String idempotencyKey) {
        try {
            web.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                    .header("Idempotency-Key", idempotencyKey)
                    .bodyValue(body(env))
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class)
                    .getResponseBody()
                    .blockLast(java.time.Duration.ofSeconds(10));
        } catch (Exception ignored) {
            // Truncated/aborted SSE is expected for timeout/reset scenarios.
        }
    }

    private String captureStreamBody(String idempotencyKey) {
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
        } catch (Exception ex) {
            // The Gateway aborts the SSE with an error after a clean upstream
            // EOF without [DONE]; partial bytes observed so far are enough.
            return "";
        }
    }

    private void awaitState(String expected) {
        var deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var state = requestState();
            if (expected.equals(state)) {
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
        return "data: {\"id\":\"chatcmpl_sse" + index + "\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1788000200,\"model\":\"mimo-v2.5-pro\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"t" + index + "\"},"
                + "\"finish_reason\":null}]}\n\n";
    }

    private static String chunkResetFrame(int index) {
        return "data: {\"id\":\"chatcmpl_rst" + index + "\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1788000200,\"model\":\"mimo-v2.5-pro\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"t\"},\"finish_reason\":null}]}\n\n";
    }

    private static String usageFrame() {
        return "data: {\"id\":\"chatcmpl_usage\",\"created\":1788000200,"
                + "\"model\":\"mimo-v2.5-pro\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}\n\n";
    }

    private static final String DONE_FRAME = "data: [DONE]\n\n";

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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
