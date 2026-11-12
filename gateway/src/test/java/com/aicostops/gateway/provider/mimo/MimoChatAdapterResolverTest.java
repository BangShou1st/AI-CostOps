package com.aicostops.gateway.provider.mimo;

import static org.junit.jupiter.api.Assertions.*;

import com.aicostops.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;

/**
 * M21 post-release SSRF regression test for MiMo adapter.
 *
 * <p>Behavioral test: verifies that the MiMo adapter's HttpClient rejects
 * loopback/private destinations by default, with no profile required, and
 * allows them only behind the explicit
 * {@code aicostops.gateway.provider.allow-private-endpoints=true} opt-in.
 * This enforces the frozen M18 DNS-rebinding / SSRF contract.
 *
 * <p>Mutation proof: if the resolver wiring line is removed from
 * MimoChatAdapter.buildHttpClient(), the default test FAILs because the
 * default client will connect to the loopback server.
 */
class MimoChatAdapterResolverTest {

    private DisposableServer server;
    private int serverPort;
    private final AtomicInteger serverHits = new AtomicInteger();

    @BeforeEach
    void setUp() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/health",
                        (request, response) -> {
                            serverHits.incrementAndGet();
                            return response.sendString(Mono.just("ok"));
                        }))
                .bindNow();
        serverPort = server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void mimoConnectsToLocalhostOnlyWhenPrivateEndpointsAreExplicitlyAllowed() {
        var adapter = createAdapter(true, false);

        HttpClient httpClient = adapter.httpClientForTest();
        Integer status = httpClient.get()
                .uri("http://localhost:" + serverPort + "/health")
                .responseSingle((response, body) ->
                        Mono.just(response.status().code()))
                .block(Duration.ofSeconds(5));

        assertEquals(200, status, "An explicit opt-in allows loopback targets");
        assertEquals(1, serverHits.get(), "Opted-in request should reach server");
    }

    @Test
    void mimoRejectsLoopbackByDefaultWithoutAnyProfile() {
        var adapter = createAdapter(false, false);

        HttpClient httpClient = adapter.httpClientForTest();
        assertLoopbackRejected(httpClient);
    }

    @Test
    void mimoRejectsLoopbackByDefaultInProdProfile() {
        var adapter = createAdapter(false, true);

        HttpClient httpClient = adapter.httpClientForTest();
        assertLoopbackRejected(httpClient);
    }

    @Test
    void mimoAdapterReportsCorrectCode() {
        var adapter = createAdapter(false, true);
        assertEquals("MIMO", adapter.adapterCode());
    }

    private void assertLoopbackRejected(HttpClient httpClient) {
        try {
            httpClient.get()
                    .uri("http://localhost:" + serverPort + "/health")
                    .responseSingle((response, body) ->
                            Mono.just(response.status().code()))
                    .block(Duration.ofSeconds(5));
            fail("Provider dispatch MUST NOT connect to loopback/private address");
        } catch (Exception ex) {
            // Expected: PublicOnlyAddressResolverGroup rejects loopback/private resolution
        }

        assertEquals(0, serverHits.get(),
                "Request MUST NOT reach loopback server (M18 DNS-rebinding contract)");
    }

    private MimoChatAdapter createAdapter(boolean allowPrivateEndpoints, boolean prodProfile) {
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(3000);
        properties.setHeaderTimeoutMs(3000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        if (allowPrivateEndpoints) {
            env.setProperty("aicostops.gateway.provider.allow-private-endpoints", "true");
        }
        if (prodProfile) {
            env.addActiveProfile("prod");
        }

        return new MimoChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);
    }
}
