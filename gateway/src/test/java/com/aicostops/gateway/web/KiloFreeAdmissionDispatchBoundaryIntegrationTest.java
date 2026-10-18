package com.aicostops.gateway.web;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Gateway-side Kilo Gateway FREE_ONLY barrier over real MySQL (2026-10-18).
 *
 * <p>The Backend already refuses to promote a non-free Kilo model, and the Kilo adapter refuses a
 * non-free model before any Provider I/O. This suite proves the Gateway does not depend on either:
 * the route below is deliberately constructed to look fully legitimate — an ACTIVE routing policy,
 * an ACTIVE candidate, an ACTIVE Pricing Version, an ACTIVE credential and a chat-capable Provider
 * Model — exactly the state a paid model would be in if it had been promoted before this round.
 * Dispatch must still be refused, and the controlled mock upstream must observe <b>zero</b> requests.
 *
 * <p>Both the normal and the streaming entry points are covered, and a control case proves an
 * admitted free Kilo model is not refused by the same gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class KiloFreeAdmissionDispatchBoundaryIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String RAW_KEY = "aic_0123456789ab_" + "A".repeat(43);
    private static final String PAID_MODEL = "deepseek-v4-pro";
    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";

    private static HttpServer upstream;
    private static final AtomicInteger upstreamCalls = new AtomicInteger();

    @Autowired
    private WebTestClient web;

    @Autowired
    private JdbcTemplate jdbc;

    private SeededEnv env;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            upstreamCalls.incrementAndGet();
            var body = "{\"id\":\"should-never-happen\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void seed() {
        upstreamCalls.set(0);
        // The default fixture seeds a MiMo route; re-point it at the Kilo adapter so the Gateway's
        // own free-admission policy is the only thing standing between the request and the Provider.
        env = GatewayTestFixture.seed(jdbc, "kilo-free-" + System.nanoTime(), HMAC_KEY, RAW_KEY,
                GatewayTestFixture.TEST_KEK, "sk-kilo-secret",
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/v1");
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void paidKiloModelWithActivePricingCredentialAndLegacyRouteIsRefusedBeforeAnyUpstreamCall() {
        retargetRouteToKiloGateway(PAID_MODEL);

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "kilo-paid-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstreamCalls.get())
                .describedAs("A PAID Kilo model must never reach Provider HTTP I/O")
                .isZero();
        assertThat(countRouteAttempts()).isZero();
    }

    @Test
    void paidKiloModelIsRefusedForStreamingToo() {
        retargetRouteToKiloGateway("glm-5.3");

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "kilo-paid-stream-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstreamCalls.get()).isZero();
        assertThat(countRouteAttempts()).isZero();
    }

    /**
     * Control case: the same fully-routable shape for an admitted free Kilo model is <em>not</em>
     * refused by the free-admission gate. The request proceeds to dispatch planning (a route attempt
     * is committed) and then fails for an unrelated transport reason, because the Kilo adapter pins
     * the public-only resolver and this mock is loopback. That contrast is what proves the refusals
     * above come from free admission rather than from a fixture that could never dispatch at all.
     */
    @Test
    void admittedFreeKiloModelPassesTheFreeAdmissionGateAndReachesDispatchPlanning() {
        retargetRouteToKiloGateway(FREE_MODEL);

        var status = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "kilo-free-admitted-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .returnResult(byte[].class)
                .getStatus();

        assertThat(status.value())
                .describedAs("An admitted free Kilo model must not be refused by free admission")
                .isNotEqualTo(403);
        assertThat(countRouteAttempts())
                .describedAs("An admitted free model must reach dispatch planning, proving the"
                        + " refusals above are free-admission refusals and not a dead fixture")
                .isGreaterThanOrEqualTo(1);
    }

    /** Other providers are unaffected: the MiMo route still dispatches normally. */
    @Test
    void nonKiloRoutesStillDispatch() {
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "mimo-unaffected-kilo-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().value(value -> assertThat(value).isNotEqualTo(403));
    }

    /**
     * Re-points the seeded route at the Kilo Gateway adapter while leaving every other legacy dispatch
     * precondition intact (ACTIVE candidate, ACTIVE pricing version, ACTIVE credential, chat-capable
     * Provider Model). {@code provider_code} is a foreign key into {@code provider_catalog}, so a
     * dedicated ACTIVE catalog row is inserted instead of renaming the MiMo one.
     */
    private void retargetRouteToKiloGateway(String providerModelName) {
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('KILO_GATEWAY','Kilo Gateway',"
                + "'KILO_GATEWAY','https://ai-gateway.kilo.ai/api/v1','ACTIVE',JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE adapter_code='KILO_GATEWAY',status='ACTIVE',"
                + "updated_at=UTC_TIMESTAMP(6)");
        jdbc.update("UPDATE provider_account SET provider_code='KILO_GATEWAY' WHERE org_id=?",
                env.orgId());
        jdbc.update("UPDATE provider_model SET provider_code='KILO_GATEWAY', provider_model_name=?"
                + " WHERE id=?", providerModelName, env.providerModelId());
        jdbc.update("UPDATE provider_connection_profile SET template_code='KILO_GATEWAY',"
                + " protocol_code='OPENAI_CHAT_COMPLETIONS', auth_type='BEARER', auth_header_name=NULL,"
                + " network_policy='DIRECT_ONLY' WHERE org_id=?", env.orgId());
        // The route must be genuinely dispatchable apart from free admission: assert the pricing,
        // credential and catalog preconditions so a future fixture change cannot silently weaken
        // this test's premise.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pricing_version WHERE org_id=?"
                + " AND provider_model_id=? AND status='ACTIVE'", Integer.class, env.orgId(),
                env.providerModelId()))
                .describedAs("the refusal must not be explained by missing pricing").isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_credential WHERE org_id=?"
                + " AND provider_account_id=? AND status='ACTIVE'", Integer.class, env.orgId(),
                env.providerAccountId()))
                .describedAs("the refusal must not be explained by a missing credential").isOne();
        assertThat(jdbc.queryForObject("SELECT adapter_code FROM provider_catalog"
                + " WHERE provider_code='KILO_GATEWAY' AND status='ACTIVE'", String.class))
                .describedAs("the refusal must not be explained by a missing adapter")
                .isEqualTo("KILO_GATEWAY");
    }

    private int countRouteAttempts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt WHERE org_id=?",
                Integer.class, env.orgId());
    }
}
