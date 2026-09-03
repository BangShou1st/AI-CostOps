package com.aicostops.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Blocking-boundary proof for the HTTP request path: every catalog/model DB
 * read triggered from {@code POST /v1/chat/completions} (model-key lookup,
 * model row, Provider credential decryption read) executes on the dedicated
 * {@code gateway-db-*} scheduler, never on a {@code reactor-http-*} event-loop
 * thread.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class CatalogBlockingBoundaryIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String MOCK_RESPONSE = """
            {"id":"chatcmpl_blocking","object":"chat.completion","created":1788000100,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static HttpServer mockUpstream;
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static final ConcurrentLinkedQueue<String> MAPPER_THREADS = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class ThreadRecordingConfiguration {
        @Bean
        @Primary
        GatewayReadMapper threadRecordingReadMapper(GatewayReadMapper delegate) {
            return (GatewayReadMapper) Proxy.newProxyInstance(
                    GatewayReadMapper.class.getClassLoader(),
                    new Class<?>[] {GatewayReadMapper.class},
                    (proxy, method, args) -> {
                        MAPPER_THREADS.add(Thread.currentThread().getName());
                        return method.invoke(delegate, args);
                    });
        }
    }

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
        MAPPER_THREADS.clear();
        UPSTREAM_CALLS.set(0);
        var mockUrl = "http://127.0.0.1:" + mockUpstream.getAddress().getPort() + "/v1";
        env = GatewayTestFixture.seed(jdbc, "blocking-" + System.nanoTime(), HMAC_KEY, rawKey(),
                GatewayTestFixture.TEST_KEK, "sk-test-secret", mockUrl);
        // Seeding uses JDBC directly; only mapper calls on the HTTP request
        // path are relevant for the event-loop assertion.
        MAPPER_THREADS.clear();
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void httpRequestPathCatalogReadsNeverRunOnEventLoop() {
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey())
                .header("Idempotency-Key", "blocking-idem-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk();

        assertThat(UPSTREAM_CALLS.get()).isEqualTo(1);
        assertThat(MAPPER_THREADS)
                .describedAs("expected catalog/model mapper reads on the HTTP request path")
                .isNotEmpty();
        assertThat(MAPPER_THREADS)
                .allSatisfy(thread -> assertThat(thread).startsWith("gateway-db-"));
        assertThat(MAPPER_THREADS.stream().filter(thread -> thread.startsWith("reactor-http-")))
                .describedAs("no mapper call may run on a Reactor Netty event-loop thread")
                .isEmpty();
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
