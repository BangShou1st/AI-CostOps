package com.aicostops.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * AIC-091 redaction: sentinel prompt/completion, Provider error body, raw
 * Gateway key, Provider secret and Idempotency-Key values never appear in
 * Gateway logs on success or on a 5xx Provider failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class GatewayRedactionTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String SENTINEL_PROMPT = "SENTINEL_PROMPT_AB12";
    private static final String SENTINEL_UPSTREAM_BODY = "SENTINEL_UPSTREAM_BODY_XY99";
    private static final String PROVIDER_SECRET = "sk-redact-secret";

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static volatile boolean upstreamThrows = false;

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
            if (upstreamThrows) {
                var body = ("{\"error\":{\"message\":\"" + SENTINEL_UPSTREAM_BODY
                        + "\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
                exchange.close();
                return;
            }
            var bytes = ("{\"id\":\"chatcmpl_redact\",\"object\":\"chat.completion\","
                    + "\"created\":1788000500,\"model\":\"mimo-v2.5-pro\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}")
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
        upstreamThrows = false;
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "redact-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, PROVIDER_SECRET, mockUrl);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void logsNeverContainPromptGatewayKeyProviderSecretOrIdempotencyOnSuccess() {
        var events = captureLogs(() -> {
            web.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                    .header("Idempotency-Key", "redact-idem-1")
                    .bodyValue(body(env, SENTINEL_PROMPT))
                    .exchange()
                    .expectStatus().isOk();
        });

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertNoSentinel(events, SENTINEL_PROMPT, rawKey(), PROVIDER_SECRET, "redact-idem-1");
    }

    @Test
    void providerErrorBodyAndOtherSecretsNeverReachLogsOn500() {
        upstreamThrows = true;

        var events = captureLogs(() -> {
            web.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer(rawKey()))
                    .header("Idempotency-Key", "redact-idem-2")
                    .bodyValue(body(env, SENTINEL_PROMPT))
                    .exchange()
                    .expectStatus().isEqualTo(502);
        });

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertNoSentinel(events, SENTINEL_PROMPT, SENTINEL_UPSTREAM_BODY, rawKey(),
                PROVIDER_SECRET, "redact-idem-2");
    }

    private static java.util.List<String> captureLogs(Runnable request) {
        var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            request.run();
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static void assertNoSentinel(java.util.List<String> events, String... sentinels) {
        for (var sentinel : sentinels) {
            assertThat(events).describedAs("log must not contain %s", sentinel)
                    .noneMatch(message -> message != null && message.contains(sentinel));
        }
    }

    private static String body(SeededEnv env, String prompt) {
        return "{\"model\":\"" + env.modelKey()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}";
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}