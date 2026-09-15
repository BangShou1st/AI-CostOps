package com.aicostops.gateway.provider.openai;

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

class OpenAiChatAdapterResolverTest {

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
    void openAiNonProdCanConnectToLocalhost() {
        var adapter = createAdapter(false);

        HttpClient httpClient = adapter.httpClientForTest();
        Integer status = httpClient.get()
                .uri("http://localhost:" + serverPort + "/health")
                .responseSingle((response, body) ->
                        Mono.just(response.status().code()))
                .block(Duration.ofSeconds(5));

        assertEquals(200, status, "Non-prod OpenAI should connect to localhost");
        assertEquals(1, serverHits.get(), "Non-prod request should reach server");
    }

    @Test
    void openAiProdCannotConnectToLocalhost() {
        var adapter = createAdapter(true);

        HttpClient httpClient = adapter.httpClientForTest();
        try {
            httpClient.get()
                    .uri("http://localhost:" + serverPort + "/health")
                    .responseSingle((response, body) ->
                            Mono.just(response.status().code()))
                    .block(Duration.ofSeconds(5));
            fail("Production OpenAI MUST NOT connect to loopback/private address");
        } catch (Exception ex) {
            // Expected: PublicOnlyAddressResolverGroup rejects loopback/private resolution
        }

        assertEquals(0, serverHits.get(),
                "Production request MUST NOT reach loopback server (M18 DNS-rebinding contract)");
    }

    @Test
    void openAiAdapterReportsCorrectCode() {
        var adapter = createAdapter(true);
        assertEquals("OPENAI", adapter.adapterCode());
    }

    private OpenAiChatAdapter createAdapter(boolean prodProfile) {
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(3000);
        properties.setHeaderTimeoutMs(3000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        if (prodProfile) {
            env.addActiveProfile("prod");
        }

        return new OpenAiChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);
    }
}