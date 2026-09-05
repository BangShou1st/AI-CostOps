package com.aicostops.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;

class OpenAiChatAdapterTest {

    private static HttpServer server;
    private static volatile boolean stream;
    private static volatile String auth;
    private static volatile String clientRequestId;
    private static volatile String body;
    private OpenAiChatAdapter adapter;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            auth = exchange.getRequestHeaders().getFirst("Authorization");
            clientRequestId = exchange.getRequestHeaders().getFirst("X-Client-Request-Id");
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var response = stream
                    ? "data: {\"id\":\"s1\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                    + "data: {\"id\":\"s1\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n"
                    : "{\"id\":\"c1\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}";
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.getResponseHeaders().set("x-request-id", "req-openai-1");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (var output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void setUp() {
        stream = false;
        auth = null;
        clientRequestId = null;
        body = null;
        adapter = new OpenAiChatAdapter(WebClient.builder(), new ObjectMapper(),
                new GatewayProperties(), new MockEnvironment());
    }

    @Test
    void sendsBearerAndReturnsProviderRequestId() {
        var result = adapter.complete(context(), command(false)).block();
        assertThat(result.providerRequestId()).isEqualTo("req-openai-1");
        assertThat(result.choices().get(0).content()).isEqualTo("hello");
        assertThat(auth).isEqualTo("Bearer bearer-secret");
        assertThat(clientRequestId).isEqualTo("route-openai");
        assertThat(body).contains("\"model\":\"gpt-test\"").doesNotContain("stream_options");
    }

    @Test
    void streamingRequestsTerminalUsageAndDone() {
        stream = true;
        var events = adapter.stream(context(), command(true)).collectList().block();
        assertThat(body).contains("\"stream\":true", "\"include_usage\":true");
        assertThat(clientRequestId).isEqualTo("route-openai");
        assertThat(events).extracting(Object::getClass)
                .containsExactly(ProviderChatStreamEvent.Delta.class,
                        ProviderChatStreamEvent.Metering.class, ProviderChatStreamEvent.Done.class);
        assertThat(((ProviderChatStreamEvent.Metering) events.get(1)).totalTokens()).isEqualTo(3);
    }

    private static ProviderCallContext context() {
        return new ProviderCallContext("OPENAI", 1, 2, "gpt-test", 3, "USD",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "BEARER_TOKEN", "bearer-secret".getBytes(StandardCharsets.UTF_8), "route-openai");
    }

    private static ChatCompletionCommand command(boolean stream) {
        return new ChatCompletionCommand("logical-chat",
                List.of(new ChatCompletionCommand.Message("user", "hi")), 128, stream);
    }
}
