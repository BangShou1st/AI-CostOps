package com.aicostops.gateway.provider.opencode;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenCode DIRECT_ONLY acceptance (M18 V3).
 *
 * <p>With JVM proxy properties deliberately poisoned at
 * {@code 127.0.0.1:7897}, OpenCode traffic must reach the controlled
 * upstream while the poison proxy observes zero requests — for normal and
 * streaming completions.
 */
class OpenCodeDirectOnlyTest {

    private static final String COMPLETION_JSON = """
            {"id":"cmpl-zen-1","object":"chat.completion","created":1,"model":"zen-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;
    private static final String SSE_BODY =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"zen-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
            + "\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";

    private HttpServer upstream;
    private HttpServer poisonProxy;
    private final AtomicInteger upstreamHits = new AtomicInteger();
    private final AtomicInteger poisonHits = new AtomicInteger();
    private final List<String> upstreamUserAgents = new CopyOnWriteArrayList<>();
    private int upstreamPort;

    @BeforeEach
    void startServers() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            upstreamHits.incrementAndGet();
            var agents = exchange.getRequestHeaders().getFirst("User-Agent");
            if (agents != null) upstreamUserAgents.add(agents);
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                payload = SSE_BODY.getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
        try {
            poisonProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 7897), 0);
        } catch (IOException ex) {
            poisonProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        poisonProxy.createContext("/", exchange -> {
            poisonHits.incrementAndGet();
            var payload = "blocked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        poisonProxy.start();
    }

    @AfterEach
    void stopServers() {
        if (upstream != null) upstream.stop(0);
        if (poisonProxy != null) poisonProxy.stop(0);
    }

    @Test
    void openCodeTrafficBypassesPoisonProxy() {
        var poisonPort = poisonProxy.getAddress().getPort();
        var saved = poisonJvmProxy(poisonPort);
        try {
            assertThat(System.getProperty("http.proxyHost")).isEqualTo("127.0.0.1");
            var adapter = new OpenCodeZenChatAdapter(
                    WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                    new DispatchEndpointGuard());
            var command = new ChatCompletionCommand("zen", List.of(
                    new ChatCompletionCommand.Message("user", "hello")), 16, false);
            var completion = adapter.complete(context(), command).block(
                    java.time.Duration.ofSeconds(20));
            assertThat(completion).isNotNull();
            assertThat(completion.choices()).isNotEmpty();
            var events = adapter.stream(context(), command).collectList()
                    .block(java.time.Duration.ofSeconds(20));
            assertThat(events).isNotEmpty();
            assertThat(upstreamHits.get()).isGreaterThanOrEqualTo(2);
            assertThat(poisonHits.get()).isZero();
            assertThat(upstreamUserAgents).anyMatch(OpenCodeZenChatAdapter.SERVER_USER_AGENT::equals);
        } finally {
            restoreJvmProxy(saved);
        }
    }

    private ProviderCallContext context() {
        return new ProviderCallContext("OPENCODE_ZEN", 1L, 2L, "zen-model", 3L, "USD",
                "http://127.0.0.1:" + upstreamPort + "/v1", "BEARER_TOKEN",
                "zen-test-secret".getBytes(StandardCharsets.UTF_8), "route-decision-test", 7L,
                "/chat/completions", "OPENAI_CHAT_COMPLETIONS", "DIRECT_ONLY", null);
    }

    private static String[] poisonJvmProxy(int poisonPort) {
        var saved = new String[] {
            System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"),
            System.getProperty("https.proxyHost"), System.getProperty("https.proxyPort") };
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(poisonPort));
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(poisonPort));
        return saved;
    }

    private static void restoreJvmProxy(String[] saved) {
        restore("http.proxyHost", saved[0]);
        restore("http.proxyPort", saved[1]);
        restore("https.proxyHost", saved[2]);
        restore("https.proxyPort", saved[3]);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
