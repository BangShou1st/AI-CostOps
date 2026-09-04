package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real MySQL proof of SAFE pre-connect failover and financial lineage. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class GatewaySafeFailoverIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String RAW_KEY = "aic_0123456789ab_" + "A".repeat(43);
    private static final String RESPONSE = """
            {"id":"chatcmpl-failover","object":"chat.completion","created":1788000100,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"from B"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static HttpServer upstream;
    private static final AtomicInteger calls = new AtomicInteger();
    private static volatile boolean failPrimaryWith500;

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    private SeededEnv env;
    private long secondAccountId;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            String requestBody;
            try (var in = exchange.getRequestBody()) {
                requestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (failPrimaryWith500
                    && "sk-test-a".equals(exchange.getRequestHeaders().getFirst("api-key"))) {
                var body = "{\"error\":{\"message\":\"synthetic provider failure\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
                exchange.close();
                return;
            }
            var streaming = requestBody.contains("\"stream\":true");
            var response = streaming
                    ? "data: {\"id\":\"stream-failover\",\"created\":1788000100,\"model\":\"mimo-v2.5-pro\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"from B\"},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"stream-failover\",\"created\":1788000100,\"model\":\"mimo-v2.5-pro\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}\n\n"
                            + "data: [DONE]\n\n"
                    : RESPONSE;
            var body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("x-request-id", "req-failover-b");
            exchange.getResponseHeaders().set("Content-Type",
                    streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void seed() {
        calls.set(0);
        failPrimaryWith500 = false;
        env = GatewayTestFixture.seed(jdbc, "safe-failover-" + System.nanoTime(), HMAC_KEY,
                RAW_KEY, GatewayTestFixture.TEST_KEK, "sk-test-a", "https://does-not-exist.invalid/v1");
        secondAccountId = GatewayTestFixture.addMimoCompatibleCandidate(jdbc, env,
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", 1);
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void safeDnsFailureReleasesAAndDispatchesBOnce() {
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "safe-failover-1")
                .bodyValue("{\"model\":\"default-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("from B")
                .jsonPath("$.usage.total_tokens").isEqualTo(8);

        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForList("SELECT attempt_no FROM gateway_route_attempt WHERE org_id=? ORDER BY attempt_no",
                Integer.class, env.orgId())).containsExactly(1, 2);
        assertThat(jdbc.queryForList("SELECT status FROM gateway_route_attempt WHERE org_id=? ORDER BY attempt_no",
                String.class, env.orgId())).containsExactly("SAFE_NO_BILLABLE_EXECUTION", "COMPLETED");
        assertThat(jdbc.queryForList("SELECT provider_account_id FROM gateway_route_attempt WHERE org_id=? ORDER BY attempt_no",
                Long.class, env.orgId())).containsExactly(env.providerAccountId(), secondAccountId);

        var holds = jdbc.queryForList("""
                SELECT br.status FROM budget_reservation br
                JOIN gateway_route_attempt ra ON ra.id=br.route_attempt_id
                WHERE ra.org_id=? ORDER BY ra.attempt_no
                """, String.class, env.orgId());
        assertThat(holds).containsExactly("RELEASED", "ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=? AND route_attempt_id="
                        + "(SELECT id FROM gateway_route_attempt WHERE org_id=? AND attempt_no=1)",
                Integer.class, env.orgId(), env.orgId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=? AND route_attempt_id="
                        + "(SELECT id FROM gateway_route_attempt WHERE org_id=? AND attempt_no=2)",
                Integer.class, env.orgId(), env.orgId())).isEqualTo(1);
    }

    @Test
    void safeDnsFailureBeforeStreamOutputSwitchesToBWithoutMixingAndEmitsOneDone() {
        var result = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "safe-failover-stream-1")
                .bodyValue("{\"model\":\"default-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        var body = String.join("", result);
        assertThat(calls).hasValue(1);
        assertThat(body).contains("from B");
        assertThat(body).doesNotContain("from A");
        assertThat(body.split("\\[DONE\\]", -1).length - 1).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM gateway_route_attempt WHERE org_id=? ORDER BY attempt_no",
                String.class, env.orgId())).containsExactly("SAFE_NO_BILLABLE_EXECUTION", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_usage_fact WHERE org_id=?",
                Integer.class, env.orgId())).isEqualTo(1);
    }

    @Test
    void billableHttp500StopsWithoutCallingB() {
        failPrimaryWith500 = true;
        jdbc.update("UPDATE provider_catalog SET base_url=? WHERE provider_code='MIMO'",
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "billable-stop-1")
                .bodyValue("{\"model\":\"default-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().is5xxServerError();

        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForList("SELECT status FROM gateway_route_attempt WHERE org_id=?",
                String.class, env.orgId())).containsExactly("BILLABLE_POSSIBLE");
        assertThat(jdbc.queryForList("SELECT status FROM budget_reservation WHERE org_id=?",
                String.class, env.orgId())).containsExactly("PENDING_HOLD");
    }
}
