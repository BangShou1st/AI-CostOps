package com.aicostops.gateway.provider.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class BridgeOpenAiCompatibleChatAdapterTest {

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static volatile boolean stream;
    private static volatile boolean stall;
    private static volatile boolean omitUsage;
    private static volatile boolean omitFinishReason;
    private static volatile boolean manyChunks;
    private static volatile int status;
    private static volatile String auth;
    private static volatile String customKey;
    private static volatile String clientRequestId;
    private static volatile String requestBody;

    private ExecutorService bridgeExecutor;
    private BridgeOpenAiCompatibleChatAdapter adapter;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            auth = exchange.getRequestHeaders().getFirst("Authorization");
            customKey = exchange.getRequestHeaders().getFirst("X-Custom-Key");
            clientRequestId = exchange.getRequestHeaders().getFirst("X-Client-Request-Id");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status != 200) {
                var failure = "{\"error\":{\"message\":\"upstream rejected\"}}";
                exchange.sendResponseHeaders(status, failure.length());
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(failure.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (stream) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    if (manyChunks) {
                        for (int index = 0; index < 300; index++) {
                            write(output, "data: {\"id\":\"s1\",\"created\":1,"
                                    + "\"model\":\"gpt-test\",\"choices\":[{\"index\":0,"
                                    + "\"delta\":{\"content\":\"x\"}}]}\n\n");
                        }
                    } else {
                        write(output, "data: {\"id\":\"s1\",\"created\":1,\"model\":\"gpt-test\","
                                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n");
                    }
                    if (stall) {
                        sleep(5_000);
                    }
                    if (!omitFinishReason) {
                        write(output, "data: {\"id\":\"s1\",\"created\":1,\"model\":\"gpt-test\","
                                + "\"choices\":[{\"index\":0,\"delta\":{},"
                                + "\"finish_reason\":\"stop\"}]}\n\n");
                    }
                    write(output, "data: {\"id\":\"s1\",\"created\":1,\"model\":\"gpt-test\","
                            + "\"choices\":[],\"usage\":{\"prompt_tokens\":1,"
                            + "\"completion_tokens\":2,\"total_tokens\":3}}\n\n");
                    write(output, "data: [DONE]\n\n");
                }
                return;
            }
            var response = "{\"id\":\"c1\",\"created\":1,\"model\":\"gpt-test\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"content\":\"hello\"},"
                    + "\"finish_reason\":\"stop\"}]"
                    + (omitUsage ? "" : ",\"usage\":{\"prompt_tokens\":1,"
                            + "\"completion_tokens\":2,\"total_tokens\":3}")
                    + "}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("x-request-id", "req-bridge-1");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        stream = false;
        stall = false;
        omitUsage = false;
        omitFinishReason = false;
        manyChunks = false;
        status = 200;
        auth = null;
        customKey = null;
        clientRequestId = null;
        requestBody = null;
        bridgeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        adapter = adapter(new GatewayProperties());
    }

    @AfterEach
    void tearDown() {
        bridgeExecutor.shutdownNow();
    }

    @Test
    void sendsBearerAndMapsCompletionUsage() {
        var completion = adapter.complete(
                context("BEARER_TOKEN", null, "bearer-secret"), command(false)).block();

        assertThat(completion.choices()).singleElement()
                .satisfies(choice -> {
                    assertThat(choice.content()).isEqualTo("hello");
                    assertThat(choice.finishReason()).isEqualTo("stop");
                });
        assertThat(completion.usage()).isNotNull();
        assertThat(completion.usage().totalTokens()).isEqualTo(3);
        assertThat(auth).isEqualTo("Bearer bearer-secret");
        assertThat(clientRequestId).isEqualTo("route-bridge");
        assertThat(requestBody).contains("\"model\":\"gpt-test\"")
                .doesNotContain("\"stream\":true");
    }

    @Test
    void usesConfiguredApiKeyHeader() {
        adapter.complete(context("API_KEY", "X-Custom-Key", "api-secret"),
                command(false)).block();

        assertThat(customKey).isEqualTo("api-secret");
        assertThat(auth).isNull();
    }

    @Test
    void noneAuthSendsNoCredential() {
        adapter.complete(context("NONE", null, null), command(false)).block();

        assertThat(auth).isNull();
        assertThat(customKey).isNull();
    }

    @Test
    void mapsStreamingDeltaMeteringAndDone() {
        stream = true;

        var events = adapter.stream(context("BEARER_TOKEN", null, "bearer-secret"),
                command(true)).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(Object::getClass)
                .containsExactly(ProviderChatStreamEvent.Delta.class,
                        ProviderChatStreamEvent.Metering.class,
                        ProviderChatStreamEvent.Done.class);
        assertThat(((ProviderChatStreamEvent.Delta) events.get(0)).deltaContent())
                .isEqualTo("hi");
        assertThat(((ProviderChatStreamEvent.Metering) events.get(1)).totalTokens())
                .isEqualTo(3);
        assertThat(requestBody).contains("\"stream\":true")
                .contains("\"include_usage\":true");
    }

    @Test
    void streamWithoutFinishReasonStillCompletes() {
        stream = true;
        omitFinishReason = true;

        var events = adapter.stream(context("BEARER_TOKEN", null, "bearer-secret"),
                command(true)).collectList().block(Duration.ofSeconds(5));

        assertThat(events).extracting(Object::getClass)
                .containsExactly(ProviderChatStreamEvent.Delta.class,
                        ProviderChatStreamEvent.Metering.class,
                        ProviderChatStreamEvent.Done.class);
    }

    @Test
    void slowSubscriberReceivesEveryChunkWithoutUnboundedBuffering() {
        stream = true;
        manyChunks = true;

        var events = adapter.stream(context("BEARER_TOKEN", null, "bearer-secret"),
                        command(true))
                .delayElements(Duration.ofMillis(1))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(events).hasSize(302);
        assertThat(events.get(events.size() - 1))
                .isInstanceOf(ProviderChatStreamEvent.Done.class);
    }

    @Test
    void missingUsageStaysAbsent() {
        omitUsage = true;

        var completion = adapter.complete(
                context("BEARER_TOKEN", null, "bearer-secret"), command(false)).block();

        assertThat(completion.usage()).isNull();
    }

    @Test
    void mapsHttpRateLimitAsBillableAndQualifying() {
        status = 429;

        assertThatThrownBy(() -> adapter.complete(
                context("BEARER_TOKEN", null, "bearer-secret"), command(false)).block())
                .isInstanceOfSatisfying(ProviderExecutionException.class, failure -> {
                    assertThat(failure.safetyOutcome())
                            .isEqualTo(ProviderSafetyOutcome.BILLABLE_POSSIBLE);
                    assertThat(failure.safetyReason())
                            .isEqualTo(ProviderSafetyReason.HTTP_RESPONSE_RECEIVED);
                    assertThat(failure.httpStatus()).isEqualTo(429);
                    assertThat(failure.healthSignal())
                            .isEqualTo(ProviderHealthSignal.QUALIFYING_FAILURE);
                });
    }

    @Test
    void mapsAuthenticationFailureAsRouteConfiguration() {
        status = 401;

        assertThatThrownBy(() -> adapter.complete(
                context("BEARER_TOKEN", null, "bearer-secret"), command(false)).block())
                .isInstanceOfSatisfying(ProviderExecutionException.class, failure -> {
                    assertThat(failure.safetyOutcome())
                            .isEqualTo(ProviderSafetyOutcome.BILLABLE_POSSIBLE);
                    assertThat(failure.httpStatus()).isEqualTo(401);
                    assertThat(failure.healthSignal())
                            .isEqualTo(ProviderHealthSignal.ROUTE_CONFIGURATION_FAILURE);
                });
    }

    @Test
    void streamIdleTimeoutTerminatesTheFlux() {
        stream = true;
        stall = true;
        var properties = new GatewayProperties();
        properties.setStreamIdleTimeoutMs(1_000);
        adapter = adapter(properties);

        StepVerifier.create(adapter.stream(
                        context("BEARER_TOKEN", null, "bearer-secret"), command(true)))
                .assertNext(event -> assertThat(event)
                        .isInstanceOf(ProviderChatStreamEvent.Delta.class))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOfSatisfying(ProviderExecutionException.class, failure -> {
                            assertThat(failure.safetyReason())
                                    .isEqualTo(ProviderSafetyReason.STREAM_TIMEOUT);
                            assertThat(failure.safetyOutcome())
                                    .isEqualTo(ProviderSafetyOutcome.BILLABLE_POSSIBLE);
                        }))
                .verify(Duration.ofSeconds(6));
    }

    private BridgeOpenAiCompatibleChatAdapter adapter(GatewayProperties properties) {
        var mapper = new ObjectMapper();
        var policy = new OutboundEndpointPolicy(true,
                Set.of("127.0.0.1", "::1", "localhost"), Set.of());
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(bridgeExecutor)
                .build();
        var transport = new HttpTransport(mapper, policy,
                Duration.ofSeconds(5), Duration.ofSeconds(5), client);
        var modelBridge = new ModelBridgeClient(new AdapterRegistry()
                .register(new PermissiveOpenAiCompatibleAdapter(
                        new OpenAiCompatibleAdapter(mapper, transport))));
        return new BridgeOpenAiCompatibleChatAdapter(
                modelBridge, bridgeExecutor, properties, new DispatchEndpointGuard(true));
    }

    private static ProviderCallContext context(
            String credentialType, String authHeaderName, String secret) {
        return new ProviderCallContext(
                BridgeOpenAiCompatibleChatAdapter.ADAPTER_CODE,
                1L, 2L, "gpt-test", 3L, "USD",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                credentialType,
                secret == null ? null : secret.getBytes(StandardCharsets.UTF_8),
                "route-bridge",
                4L,
                "/chat/completions",
                "OPENAI_CHAT_COMPLETIONS",
                "DIRECT_PUBLIC_ONLY",
                authHeaderName);
    }

    private static ChatCompletionCommand command(boolean streaming) {
        return new ChatCompletionCommand("logical-chat",
                List.of(new ChatCompletionCommand.Message("user", "hi")), 128, streaming);
    }

    private static void write(OutputStream output, String value) throws java.io.IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
