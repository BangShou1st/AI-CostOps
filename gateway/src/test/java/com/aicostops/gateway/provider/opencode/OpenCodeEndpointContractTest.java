package com.aicostops.gateway.provider.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenCode Zen endpoint contract (M18 V3 maintenance).
 *
 * <p>Pins the exact upstream request the OpenCode adapter must emit: the frozen
 * {@link ProviderCallContext} base URL plus the profile completion path, with no
 * duplicated {@code /v1}, no lost path segment and no multi-concatenation, for both the
 * normal and streaming Chat Completions calls. Also pins the server-owned User-Agent,
 * the Bearer credential header, {@code stream_options.include_usage}, and the
 * no-JVM/OS-proxy (DIRECT_ONLY) transport policy.
 *
 * <p><b>Versioned decision (2026-10-17).</b> The built-in template's inference endpoint is now the
 * shared CLI inference base {@code https://opencode.ai/inference/v1} (the frozen
 * {@link ProviderCallContext} stays the dispatch authority). This test also locks the CLI request
 * body envelope (the shell/read reserved tools) and the rule that a quota-exhausted {@code 429} is a normal
 * bounded outcome for the Zen free tier, not a qualifying provider failure.
 */
class OpenCodeEndpointContractTest {

    private static final String COMPLETION_JSON = """
            {"id":"cmpl-zen-1","object":"chat.completion","created":1,"model":"zen-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;
    private static final String SSE_BODY =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"zen-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
            + "\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"zen-model\",\"choices\":[],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}\n\n"
            + "data: [DONE]\n\n";
    private static final String SECRET = "zen-test-secret";

    private HttpServer upstream;
    private HttpServer poisonProxy;
    private final AtomicInteger upstreamHits = new AtomicInteger();
    private final AtomicInteger poisonHits = new AtomicInteger();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final List<java.util.Map<String, List<String>>> wireHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger forcedStatus = new AtomicInteger();
    private final AtomicReference<String> forcedSseBody = new AtomicReference<>();
    private final AtomicBoolean truncateSseResponse = new AtomicBoolean();
    private int upstreamPort;

    @BeforeEach
    void startServers() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The built-in template now uses the CLI inference base .../inference/v1; the local mock keeps
        // the same "/inference/v1/chat/completions" shape so a duplicated "/v1" would show up here.
        upstream.createContext("/inference/v1/chat/completions", exchange -> {
            upstreamHits.incrementAndGet();
            wireHeaders.add(java.util.Map.copyOf(exchange.getRequestHeaders()));
            requestPaths.add(exchange.getRequestURI().getPath());
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) authorizationHeaders.add(authorization);
            var agent = exchange.getRequestHeaders().getFirst("User-Agent");
            if (agent != null) userAgents.add(agent);
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            int forced = forcedStatus.get();
            if (forced != 0) {
                var forcedPayload = "forced".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(forced, forcedPayload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(forcedPayload);
                }
                return;
            }
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                var configuredSseBody = forcedSseBody.get();
                payload = (configuredSseBody == null ? SSE_BODY : configuredSseBody)
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, truncateSseResponse.get() ? payload.length + 64 : payload.length);
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
    void nonStreamingCallHitsTheExactZenChatCompletionsPath() {
        withPoisonedJvmProxy(() -> {
            var adapter = adapter();
            var completion = adapter.complete(context(), command()).block(java.time.Duration.ofSeconds(20));

            // The mock answers the required streaming envelope, so the aggregated completion carries the
            // concatenated deltas ("hi") and the upstream metering, never a fabricated zero usage.
            assertThat(completion).isNotNull();
            assertThat(completion.choices()).isNotEmpty();
            assertThat(completion.choices().get(0).content()).isEqualTo("hi");
            assertThat(completion.choices().get(0).finishReason()).isEqualTo("stop");
            assertThat(completion.providerModel()).isEqualTo("zen-model");
            assertThat(completion.usage()).isNotNull();
            assertThat(completion.usage().totalTokens()).isEqualTo(8);

            assertThat(requestPaths).containsExactly("/inference/v1/chat/completions");
            assertThat(countOccurrences(requestPaths.get(0), "/v1")).isEqualTo(1);
            assertThat(authorizationHeaders).containsExactly("Bearer " + SECRET);
            assertThat(userAgents).allMatch(OpenCodeZenChatAdapter.SERVER_USER_AGENT::equals);
            assertThat(requestBodies.get(0)).contains("\"stream\":true");
            assertThat(poisonHits.get()).isZero();
            assertThat(upstreamHits.get()).isEqualTo(1);
        });
    }

    @Test
    void streamingCallHitsTheSamePathAndRequestsUsage() {
        withPoisonedJvmProxy(() -> {
            var adapter = adapter();
            var events = adapter.stream(context(), command()).collectList()
                    .block(java.time.Duration.ofSeconds(20));

            assertThat(events).isNotNull();
            assertThat(events).anySatisfy(event ->
                    assertThat(event).isInstanceOf(ProviderChatStreamEvent.Delta.class));
            assertThat(events).anySatisfy(event ->
                    assertThat(event).isInstanceOf(ProviderChatStreamEvent.Metering.class));
            assertThat(events).anySatisfy(event ->
                    assertThat(event).isInstanceOf(ProviderChatStreamEvent.Done.class));

            assertThat(requestPaths).containsExactly("/inference/v1/chat/completions");
            assertThat(countOccurrences(requestPaths.get(0), "/v1")).isEqualTo(1);
            assertThat(authorizationHeaders).containsExactly("Bearer " + SECRET);
            assertThat(userAgents).allMatch(OpenCodeZenChatAdapter.SERVER_USER_AGENT::equals);
            // Chat Completions streaming must ask the provider for usage so the Gateway can meter.
            assertThat(requestBodies.get(0)).contains("\"stream\":true");
            assertThat(requestBodies.get(0)).contains("\"include_usage\":true");
            assertThat(poisonHits.get()).isZero();
        });
    }

    @Test
    void streamingBodyCarriesTheCliToolEnvelope() {
        withPoisonedJvmProxy(() -> {
            var adapter = adapter();
            adapter.stream(context(), command()).collectList().block(java.time.Duration.ofSeconds(20));

            var body = requestBodies.get(0);
            assertThat(body).contains("\"stream\":true");
            assertThat(body).contains("\"include_usage\":true");
            assertThat(body).contains("\"tools\"");
            var headers = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(wireHeaders.get(0));
            assertThat(headers.get("Accept")).containsExactly("*/*");
            assertThat(headers.get("User-Agent")).containsExactly(OpenCodeZenChatAdapter.SERVER_USER_AGENT);
            assertThat(headers.get("x-opencode-client")).containsExactly("cli");
            assertThat(headers.get("x-opencode-project").get(0)).matches("[0-9a-f]{40}");
            assertThat(headers.get("x-opencode-session")).hasSize(1);
            assertThat(headers.get("x-opencode-session").get(0))
                    .matches("ses_[0-9a-f]{12}[0-9A-Za-z]{14}");
            assertThat(headers.get("x-opencode-session-id")).isEqualTo(headers.get("x-opencode-session"));
            assertThat(headers).doesNotContainKeys("x-opencode-request", "x-session-affinity", "x-session-id");
            var json = new ObjectMapper().readTree(body);
            var reservedTools = com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy.reservedToolNames();
            assertThat(json.path("tools")).hasSize(reservedTools.size());
            for (var tool : reservedTools) {
                assertThat(body).contains("\"" + tool + "\"");
            }
        });
    }

    @Test
    void nonStreamingCallIsServedByTheRequiredStreamingEnvelopeAndAggregated() {
        withPoisonedJvmProxy(() -> {
            var adapter = adapter();
            var completion = adapter.complete(context(), command()).block(java.time.Duration.ofSeconds(20));

            var body = requestBodies.get(0);
            // Verified live against the real upstream (2026-10-17): "stream":false is rejected with
            // 403 FreeTierError, so even a non-streaming caller must be served by the full CLI envelope.
            assertThat(body).contains("\"stream\":true");
            assertThat(body).contains("\"include_usage\":true");
            assertThat(body).contains("\"tools\"");

            assertThat(completion).isNotNull();
            assertThat(completion.choices()).hasSize(1);
            assertThat(completion.choices().get(0).content()).isEqualTo("hi");
            assertThat(completion.choices().get(0).finishReason()).isEqualTo("stop");
            assertThat(completion.usage()).isNotNull();
            assertThat(completion.usage().promptTokens()).isEqualTo(5);
            assertThat(completion.usage().completionTokens()).isEqualTo(3);
        });
    }

    @Test
    void nonStreamingAggregationRejectsDeltaAndUsageWithoutDone() {
        forcedSseBody.set(SSE_BODY.replace("data: [DONE]\n\n", ""));
        var adapter = adapter();

        assertThatThrownBy(() -> adapter.complete(context(), command())
                .block(java.time.Duration.ofSeconds(20)))
                .isInstanceOf(com.aicostops.gateway.provider.ProviderExecutionException.class)
                .satisfies(ex -> {
                    var providerError = (com.aicostops.gateway.provider.ProviderExecutionException) ex;
                    assertThat(providerError.safetyOutcome())
                            .isEqualTo(com.aicostops.gateway.provider.ProviderSafetyOutcome.BILLABLE_POSSIBLE);
                    assertThat(providerError.safetyReason())
                            .isEqualTo(com.aicostops.gateway.provider.ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE);
                    assertThat(providerError.responseStarted()).isTrue();
                });
        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void nonStreamingAggregationRejectsDoneWithoutCompletionContent() {
        forcedSseBody.set("data: [DONE]\n\n");
        var adapter = adapter();

        assertThatThrownBy(() -> adapter.complete(context(), command())
                .block(java.time.Duration.ofSeconds(20)))
                .isInstanceOf(com.aicostops.gateway.provider.ProviderExecutionException.class)
                .satisfies(ex -> {
                    var providerError = (com.aicostops.gateway.provider.ProviderExecutionException) ex;
                    assertThat(providerError.safetyReason())
                            .isEqualTo(com.aicostops.gateway.provider.ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE);
                    assertThat(providerError.responseStarted()).isTrue();
                });
        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void nonStreamingAggregationRejectsAnInterruptedUpstreamStreamWithoutRetry() {
        forcedSseBody.set(SSE_BODY.replace("data: [DONE]\n\n", ""));
        truncateSseResponse.set(true);
        var adapter = adapter();

        assertThatThrownBy(() -> adapter.complete(context(), command())
                .block(java.time.Duration.ofSeconds(20)))
                .isInstanceOf(com.aicostops.gateway.provider.ProviderExecutionException.class)
                .satisfies(ex -> assertThat(((com.aicostops.gateway.provider.ProviderExecutionException) ex)
                        .safetyOutcome()).isEqualTo(
                                com.aicostops.gateway.provider.ProviderSafetyOutcome.BILLABLE_POSSIBLE));
        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void quotaExhausted429IsNotAQualifyingFailure() {
        forcedStatus.set(429);
        var adapter = adapter();
        assertThatThrownBy(() -> adapter.complete(context(), command())
                .block(java.time.Duration.ofSeconds(20)))
                .isInstanceOf(com.aicostops.gateway.provider.ProviderExecutionException.class)
                .satisfies(ex -> assertThat(((com.aicostops.gateway.provider.ProviderExecutionException) ex)
                        .healthSignal()).isEqualTo(com.aicostops.gateway.provider.ProviderHealthSignal.NONE));
    }

    @Test
    void providerSecretNeverAppearsInTheContextToString() {
        var context = context();
        assertThat(context.toString()).doesNotContain(SECRET);
        assertThat(context.toString()).doesNotContain("zen/v1");
    }

    private OpenCodeZenChatAdapter adapter() {
        return new OpenCodeZenChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard());
    }

    private ChatCompletionCommand command() {
        return new ChatCompletionCommand("zen", List.of(
                new ChatCompletionCommand.Message("user", "hello")), 16, false);
    }

    /**
     * Base carries the CLI inference prefix while the path stays {@code /chat/completions}; the
     * adapter must compose them without duplicating {@code /v1}.
     */
    private ProviderCallContext context() {
        return new ProviderCallContext("OPENCODE_ZEN", 1L, 2L, "zen-model", 3L, "USD",
                "http://127.0.0.1:" + upstreamPort + "/inference/v1", "BEARER_TOKEN",
                SECRET.getBytes(StandardCharsets.UTF_8), "route-decision-test", 7L,
                "/chat/completions", "OPENAI_CHAT_COMPLETIONS", "DIRECT_ONLY", null);
    }

    private static int countOccurrences(String value, String token) {
        var count = 0;
        var index = 0;
        while ((index = value.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }

    private void withPoisonedJvmProxy(Runnable body) {
        var poisonPort = poisonProxy.getAddress().getPort();
        var saved = new String[] {
            System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"),
            System.getProperty("https.proxyHost"), System.getProperty("https.proxyPort") };
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(poisonPort));
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(poisonPort));
        try {
            body.run();
        } finally {
            restore("http.proxyHost", saved[0]);
            restore("http.proxyPort", saved[1]);
            restore("https.proxyHost", saved[2]);
            restore("https.proxyPort", saved[3]);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
