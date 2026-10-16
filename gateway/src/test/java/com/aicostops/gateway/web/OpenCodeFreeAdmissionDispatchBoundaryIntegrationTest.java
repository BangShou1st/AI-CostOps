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
 * Gateway-side OpenCode Zen FREE_ONLY barrier over real MySQL (2026-10-16).
 *
 * <p>The Backend already refuses to promote a paid OpenCode model. This suite proves the Gateway
 * does not depend on that: the route below is deliberately constructed to look fully legitimate —
 * an ACTIVE routing policy, an ACTIVE candidate, an ACTIVE Pricing Version, an ACTIVE credential
 * and a chat-capable Provider Model, plus a recorded successful probe — exactly the state a model
 * promoted before this round would be in. Dispatch must still be refused, and the controlled mock
 * upstream must observe <b>zero</b> requests.
 *
 * <p>Both the normal and the streaming entry points are covered, because they reach the Provider
 * through different controller paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("integration")
class OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final String RAW_KEY = "aic_0123456789ab_" + "A".repeat(43);

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
        // The default fixture seeds a MiMo route; re-point it at the OpenCode Zen adapter so the
        // Gateway's own free-admission policy is the only thing standing between the request and
        // the Provider.
        env = GatewayTestFixture.seed(jdbc, "opencode-free-" + System.nanoTime(), HMAC_KEY, RAW_KEY,
                GatewayTestFixture.TEST_KEK, "sk-opencode-secret",
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/zen/v1");
    }

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void paidOpenCodeModelIsRefusedBeforeAnyUpstreamCall() {
        retargetRouteToOpenCodeZen("deepseek-v4-pro");

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "opencode-paid-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstreamCalls.get())
                .describedAs("A PAID OpenCode model must never reach Provider HTTP I/O")
                .isZero();
        assertThat(countRouteAttempts()).isZero();
    }

    @Test
    void paidOpenCodeModelIsRefusedForStreamingToo() {
        retargetRouteToOpenCodeZen("glm-5.3");

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "opencode-paid-stream-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstreamCalls.get()).isZero();
        assertThat(countRouteAttempts()).isZero();
    }

    @Test
    void delistedAndUnknownOpenCodeModelsAreRefusedBeforeAnyUpstreamCall() {
        for (var model : new String[] {
                "mimo-v2.5-free", "deepseek-v4-flash-free", "future-unknown-model",
                "deepseek-v4.1-flash-free", "cat-jam-free"}) {
            upstreamCalls.set(0);
            retargetRouteToOpenCodeZen(model);

            web.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                    .header("Idempotency-Key", "opencode-refused-" + model)
                    .bodyValue("{\"model\":\"" + env.modelKey()
                            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                    .exchange()
                    .expectStatus().isForbidden();

            assertThat(upstreamCalls.get())
                    .describedAs(model + " has no current free evidence and must fail closed")
                    .isZero();
            assertThat(countRouteAttempts()).as(model).isZero();
        }
    }

    /**
     * Control case: the same fully-routable shape for an admitted free model is <em>not</em> refused
     * by the free-admission gate. The request proceeds to dispatch planning (a route attempt is
     * committed) and then fails for an unrelated transport reason, because the OpenCode adapter pins
     * the public-only resolver and this mock is loopback. That contrast is what proves the refusals
     * above come from free admission rather than from a fixture that could never dispatch at all.
     */
    @Test
    void admittedFreeOpenCodeModelPassesTheFreeAdmissionGateAndReachesDispatchPlanning() {
        retargetRouteToOpenCodeZen("big-pickle");

        var status = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "opencode-free-admitted-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .returnResult(byte[].class)
                .getStatus();

        assertThat(status.value())
                .describedAs("An admitted free model must not be refused by free admission")
                .isNotEqualTo(403);
        assertThat(countRouteAttempts())
                .describedAs("An admitted free model must reach dispatch planning, proving the"
                        + " refusals above are free-admission refusals and not a dead fixture")
                .isGreaterThanOrEqualTo(1);
    }

    /** Other providers are unaffected: the MiMo route still dispatches normally. */
    @Test
    void nonOpenCodeRoutesStillDispatch() {
        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "mimo-unaffected-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().value(value -> assertThat(value).isNotEqualTo(403));
    }

    /**
     * Requirement #4: the Gateway credential-readiness flag requires an ACTIVE Provider credential,
     * but an admitted free Zen model is dispatched with the shared {@code public} default and needs
     * none. With no stored credential the admitted free model must still reach dispatch planning —
     * this is exactly the readiness-versus-selection conflict, and it must not be "fixed" by writing
     * a fabricated ACTIVE credential row.
     */
    @Test
    void admittedFreeOpenCodeModelDispatchesWithoutAStoredCredential() {
        retargetRouteToOpenCodeZen("big-pickle");
        jdbc.update("DELETE FROM provider_credential WHERE org_id=?", env.orgId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_credential WHERE org_id=?",
                Integer.class, env.orgId()))
                .describedAs("this scenario deliberately has no stored Provider credential")
                .isZero();

        var status = web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "opencode-free-keyless-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .returnResult(byte[].class)
                .getStatus();

        assertThat(status.value())
                .describedAs("a free keyless Zen model must not be refused for a missing credential")
                .isNotEqualTo(403);
        assertThat(countRouteAttempts())
                .describedAs("it must reach dispatch planning, proving readiness did not block it")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * The keyless exemption is scoped to admitted free models only: a paid Zen model with no stored
     * credential stays refused before any Provider HTTP I/O, so readiness is still enforced wherever
     * the request would actually need a key.
     */
    @Test
    void paidOpenCodeModelWithNoStoredCredentialIsStillRefused() {
        retargetRouteToOpenCodeZen("deepseek-v4-pro");
        jdbc.update("DELETE FROM provider_credential WHERE org_id=?", env.orgId());

        web.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + RAW_KEY)
                .header("Idempotency-Key", "opencode-paid-keyless-1")
                .bodyValue("{\"model\":\"" + env.modelKey()
                        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(upstreamCalls.get()).isZero();
        assertThat(countRouteAttempts()).isZero();
    }

    /**
     * Re-points the seeded route at the OpenCode Zen adapter while leaving every other legacy
     * dispatch precondition intact (ACTIVE candidate, ACTIVE pricing version, ACTIVE credential,
     * chat-capable provider model). {@code provider_code} is a foreign key into
     * {@code provider_catalog}, so a dedicated ACTIVE catalog row is inserted instead of renaming
     * the MiMo one. The adapter code is the Gateway-side discriminator the free-admission policy
     * keys on, which mirrors how a model promoted before this round would already look.
     */
    private void retargetRouteToOpenCodeZen(String providerModelName) {
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('OPENCODE_ZEN','OpenCode Zen',"
                + "'OPENCODE_ZEN','https://opencode.ai/zen/v1','ACTIVE',JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE adapter_code='OPENCODE_ZEN',status='ACTIVE',"
                + "updated_at=UTC_TIMESTAMP(6)");
        jdbc.update("UPDATE provider_account SET provider_code='OPENCODE_ZEN' WHERE org_id=?",
                env.orgId());
        jdbc.update("UPDATE provider_model SET provider_code='OPENCODE_ZEN', provider_model_name=?"
                + " WHERE id=?", providerModelName, env.providerModelId());
        jdbc.update("UPDATE provider_connection_profile SET template_code='OPENCODE_ZEN',"
                + " protocol_code='OPENAI_CHAT_COMPLETIONS', auth_type='BEARER', auth_header_name=NULL,"
                + " network_policy='DIRECT_ONLY' WHERE org_id=?", env.orgId());
        // The route must be genuinely dispatchable apart from free admission: assert the pricing,
        // credential and catalog preconditions so a future fixture change cannot silently weaken
        // this test's premise (a refusal that was really a missing-pricing rejection, or a
        // missing-adapter rejection, would prove nothing about free admission).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pricing_version WHERE org_id=?"
                + " AND provider_model_id=? AND status='ACTIVE'", Integer.class, env.orgId(),
                env.providerModelId()))
                .describedAs("the refusal must not be explained by missing pricing").isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_credential WHERE org_id=?"
                + " AND provider_account_id=? AND status='ACTIVE'", Integer.class, env.orgId(),
                env.providerAccountId()))
                .describedAs("the refusal must not be explained by a missing credential").isOne();
        assertThat(jdbc.queryForObject("SELECT adapter_code FROM provider_catalog"
                + " WHERE provider_code='OPENCODE_ZEN' AND status='ACTIVE'", String.class))
                .describedAs("the refusal must not be explained by a missing adapter")
                .isEqualTo("OPENCODE_ZEN");
    }

    private int countRouteAttempts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_route_attempt WHERE org_id=?",
                Integer.class, env.orgId());
    }
}
