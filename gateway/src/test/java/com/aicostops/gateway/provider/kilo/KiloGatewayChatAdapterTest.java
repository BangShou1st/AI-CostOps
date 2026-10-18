package com.aicostops.gateway.provider.kilo;

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
 * Kilo Gateway adapter endpoint contract (M18 V3; provider onboarded 2026-10-18).
 *
 * <p>Pins the exact upstream request the Kilo adapter must emit: the frozen
 * {@link ProviderCallContext} base URL ({@code https://ai-gateway.kilo.ai/api/v1}) plus the profile
 * completion path, with no duplicated {@code /v1} and no lost segment. Kilo is a plain
 * OpenAI-compatible gateway, so the body must NOT carry the OpenCode Zen CLI tool envelope; a free
 * model must be dispatched with no {@code Authorization} header; DIRECT_ONLY must ignore any JVM
 * proxy; and a quota-exhausted {@code 429} must not be a qualifying failure.
 *
 * <p><b>FREE_ONLY is enforced by the adapter itself (2026-10-18).</b> The adapter rejects every
 * non-free model before any HTTP I/O — even a fully-formed request carrying a valid stored Bearer
 * key — so the built-in template can never dispatch a paid Kilo model, independently of the
 * candidate-eligibility barrier.
 */
class KiloGatewayChatAdapterTest {

    private static final String COMPLETION_JSON = """
            {"id":"cmpl-kilo-1","object":"chat.completion","created":1,"model":"kilo-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;
    private static final String SSE_BODY =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"kilo-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
            + "\"finish_reason\":null}]}\n\n"
            + "data: [DONE]\n\n";
    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";
    private static final String PAID_MODEL = "deepseek-v4-pro";
    private static final String SECRET = "kilo-test-secret";

    private HttpServer upstream;
    private HttpServer poisonProxy;
    private final AtomicInteger upstreamHits = new AtomicInteger();
    private final AtomicInteger poisonHits = new AtomicInteger();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger forcedStatus = new AtomicInteger();
    private int upstreamPort;

    @BeforeEach
    void startServers() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The base carries the "/api/v1" prefix while the path stays "/chat/completions"; a
        // duplicated "/v1" would show up on this exact mock path.
        upstream.createContext("/api/v1/chat/completions", exchange -> {
            upstreamHits.incrementAndGet();
            requestPaths.add(exchange.getRequestURI().getPath());
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) authorizationHeaders.add(authorization);
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
            if (requestBodies.get(requestBodies.size() - 1).contains("\"stream\":true")) {
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
    void freeModelIsDispatchedAnonymouslyOnTheExactKiloPath() {
        withPoisonedJvmProxy(() -> {
            var adapter = adapter();
            var completion = adapter.complete(freeContext(), command())
                    .block(java.time.Duration.ofSeconds(20));

            assertThat(completion).isNotNull();
            assertThat(completion.choices().get(0).content()).isEqualTo("ok");
            assertThat(completion.providerModel()).isEqualTo("kilo-model");

            assertThat(requestPaths).containsExactly("/api/v1/chat/completions");
            assertThat(countOccurrences(requestPaths.get(0), "/v1")).isEqualTo(1);
            // A free Kilo model is called anonymously: no Authorization header at all.
            assertThat(authorizationHeaders).isEmpty();
            // Kilo is a plain OpenAI-compatible gateway: it must not carry the Zen CLI tool envelope.
            assertThat(requestBodies.get(0)).doesNotContain("\"tools\"");
            assertThat(requestBodies.get(0)).doesNotContain("\"include_usage\"");
            assertThat(requestBodies.get(0)).contains("\"stream\":false");
            assertThat(poisonHits.get()).isZero();
            assertThat(upstreamHits.get()).isEqualTo(1);
        });
    }

    @Test
    void paidModelWithAValidBearerKeyIsRefusedBeforeAnyNetworkCall() {
        // FREE_ONLY is enforced by the adapter itself: a fully-formed paid request with the stored
        // Bearer key must be refused before any Provider HTTP I/O.
        var adapter = adapter();
        assertThatThrownBy(() -> adapter.complete(paidContext(), command()))
                .isInstanceOf(ProviderExecutionException.class)
                .satisfies(ex -> {
                    var failure = (ProviderExecutionException) ex;
                    assertThat(failure.safetyOutcome())
                            .isEqualTo(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION);
                    assertThat(failure.safetyReason())
                            .isEqualTo(ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE);
                    assertThat(failure.healthSignal()).isEqualTo(ProviderHealthSignal.NONE);
                });
        assertThat(upstreamHits.get()).isZero();
        assertThat(authorizationHeaders).isEmpty();
        assertThat(poisonHits.get()).isZero();
    }

    @Test
    void paidModelStreamingIsRefusedBeforeAnyNetworkCall() {
        var adapter = adapter();
        assertThatThrownBy(() -> adapter.stream(paidContext(), command()))
                .isInstanceOf(ProviderExecutionException.class)
                .satisfies(ex -> assertThat(((ProviderExecutionException) ex).safetyOutcome())
                        .isEqualTo(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION));
        assertThat(upstreamHits.get()).isZero();
        assertThat(poisonHits.get()).isZero();
    }

    @Test
    void paidModelWithoutAKeyFailsClosedBeforeAnyNetworkCall() {
        var adapter = adapter();
        assertThatThrownBy(() -> adapter.complete(paidKeylessContext(), command()))
                .isInstanceOf(ProviderExecutionException.class)
                .satisfies(ex -> assertThat(((ProviderExecutionException) ex).safetyOutcome())
                        .isEqualTo(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION));
        assertThat(upstreamHits.get()).isZero();
        assertThat(poisonHits.get()).isZero();
    }

    @Test
    void quotaExhausted429IsNotAQualifyingFailure() {
        forcedStatus.set(429);
        var adapter = adapter();
        assertThatThrownBy(() -> adapter.complete(freeContext(), command())
                .block(java.time.Duration.ofSeconds(20)))
                .isInstanceOf(ProviderExecutionException.class)
                .satisfies(ex -> assertThat(((ProviderExecutionException) ex).healthSignal())
                        .isEqualTo(ProviderHealthSignal.NONE));
    }

    @Test
    void streamingFreeModelHitsTheSamePathAndRequestsUsage() {
        withPoisonedJvmProxy(() -> {
            var adapter = adapter();
            var events = adapter.stream(freeContext(), command()).collectList()
                    .block(java.time.Duration.ofSeconds(20));

            assertThat(events).isNotNull();
            assertThat(events).anySatisfy(event ->
                    assertThat(event).isInstanceOf(ProviderChatStreamEvent.Delta.class));
            assertThat(events).anySatisfy(event ->
                    assertThat(event).isInstanceOf(ProviderChatStreamEvent.Done.class));

            assertThat(requestPaths).containsExactly("/api/v1/chat/completions");
            assertThat(countOccurrences(requestPaths.get(0), "/v1")).isEqualTo(1);
            assertThat(authorizationHeaders).isEmpty();
            assertThat(requestBodies.get(0)).contains("\"stream\":true");
            // The shared OpenAI-compatible base still asks for usage so the Gateway can meter.
            assertThat(requestBodies.get(0)).contains("\"include_usage\":true");
            assertThat(poisonHits.get()).isZero();
        });
    }

    private KiloGatewayChatAdapter adapter() {
        return new KiloGatewayChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard());
    }

    private ChatCompletionCommand command() {
        return new ChatCompletionCommand("kilo", List.of(
                new ChatCompletionCommand.Message("user", "hello")), 16, false);
    }

    private ProviderCallContext freeContext() {
        return context(FREE_MODEL, "NONE", null);
    }

    private ProviderCallContext paidContext() {
        return context(PAID_MODEL, "BEARER_TOKEN", SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private ProviderCallContext paidKeylessContext() {
        return context(PAID_MODEL, "NONE", null);
    }

    private ProviderCallContext context(String model, String credentialType, byte[] secret) {
        return new ProviderCallContext("KILO_GATEWAY", 1L, 2L, model, 3L, "USD",
                "http://127.0.0.1:" + upstreamPort + "/api/v1", credentialType, secret,
                "route-decision-test", 7L, "/chat/completions", "OPENAI_CHAT_COMPLETIONS",
                "DIRECT_ONLY", null);
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
