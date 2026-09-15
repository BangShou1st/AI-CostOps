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
 * loopback/private destinations in production profile, while allowing them
 * in non-production profiles. This enforces the frozen M18 DNS-rebinding /
 * SSRF contract.
 *
 * <p>Mutation proof: if the resolver wiring line is removed from
 * MimoChatAdapter.buildHttpClient(), the prod test FAILs because the prod
 * client will connect to the loopback server.
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
    void mimoNonProdCanConnectToLocalhost() {
        var adapter = createAdapter(false);

        HttpClient httpClient = adapter.httpClientForTest();
        Integer status = httpClient.get()
                .uri("http://localhost:" + serverPort + "/health")
                .responseSingle((response, body) ->
                        Mono.just(response.status().code()))
                .block(Duration.ofSeconds(5));

        assertEquals(200, status, "Non-prod MiMo should connect to localhost");
        assertEquals(1, serverHits.get(), "Non-prod request should reach server");
    }

    @Test
    void mimoProdCannotConnectToLocalhost() {
        var adapter = createAdapter(true);

        HttpClient httpClient = adapter.httpClientForTest();
        try {
            httpClient.get()
                    .uri("http://localhost:" + serverPort + "/health")
                    .responseSingle((response, body) ->
                            Mono.just(response.status().code()))
                    .block(Duration.ofSeconds(5));
            fail("Production MiMo MUST NOT connect to loopback/private address");
        } catch (Exception ex) {
            // Expected: PublicOnlyAddressResolverGroup rejects loopback/private resolution
        }

        assertEquals(0, serverHits.get(),
                "Production request MUST NOT reach loopback server (M18 DNS-rebinding contract)");
    }

    @Test
    void mimoAdapterReportsCorrectCode() {
        var adapter = createAdapter(true);
        assertEquals("MIMO", adapter.adapterCode());
    }

    private MimoChatAdapter createAdapter(boolean prodProfile) {
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(3000);
        properties.setHeaderTimeoutMs(3000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        if (prodProfile) {
            env.addActiveProfile("prod");
        }

        return new MimoChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);
    }
}