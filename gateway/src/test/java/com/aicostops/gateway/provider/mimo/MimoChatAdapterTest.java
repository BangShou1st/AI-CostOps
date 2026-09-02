package com.aicostops.gateway.provider.mimo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * AIC-097 MiMo adapter against a controllable HTTP upstream: wire mapping,
 * auth header, response normalization, provider-error gating, exactly one
 * Provider request for 429/500/503/reset, and no automatic retry.
 */
class MimoChatAdapterTest {

    private static final String SECRET = "sk-adapter-test";
    private static final String OK_RESPONSE = """
            {"id":"chatcmpl_mock123","object":"chat.completion","created":1788000000,
             "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
             "content":"Hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    enum Scenario {
        OK, NO_USAGE, ERROR_400, ERROR_401, ERROR_403, ERROR_413, ERROR_429, ERROR_500, ERROR_503,
        DELAY
    }

    private static HttpServer server;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile Scenario scenario = Scenario.OK;
    private static volatile String lastPath;
    private static volatile String lastAuthHeader;
    private static volatile String lastBody;

    private MimoChatAdapter adapter;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            REQUESTS.incrementAndGet();
            lastPath = exchange.getRequestURI().getPath();
            lastAuthHeader = exchange.getRequestHeaders().getFirst("api-key");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (scenario) {
                case OK -> respond(exchange, 200, OK_RESPONSE);
                case NO_USAGE -> respond(exchange, 200, """
                        {"id":"chatcmpl_mock124","object":"chat.completion","created":1788000001,
                         "model":"mimo-v2.5-pro","choices":[{"index":0,"message":{"role":"assistant",
                         "content":"Hi"},"finish_reason":"stop"}]}
                        """);
                case ERROR_400 -> respond(exchange, 400, "{\"error\":{\"message\":\"bad request body\"}}");
                case ERROR_401 -> respond(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
                case ERROR_403 -> respond(exchange, 403, "{\"error\":{\"message\":\"forbidden\"}}");
                case ERROR_413 -> respond(exchange, 413, "{\"error\":{\"message\":\"too large\"}}");
                case ERROR_429 -> respond(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
                case ERROR_500 -> respond(exchange, 500, "{\"error\":{\"message\":\"boom\"}}");
                case ERROR_503 -> respond(exchange, 503, "{\"error\":{\"message\":\"unavailable\"}}");
                case DELAY -> sleep(2000L);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        scenario = Scenario.OK;
        REQUESTS.set(0);
        lastPath = null;
        lastAuthHeader = null;
        lastBody = null;
        adapter = new MimoChatAdapter(WebClient.builder(), new ObjectMapper(), properties());
    }

    @Test
    void sendsServerGovernedPathModelAndApiKeyWithoutStream() {
        StepVerifier.create(adapter.complete(context(), command()))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(lastPath).isEqualTo("/v1/chat/completions");
        assertThat(lastAuthHeader).isEqualTo(SECRET);
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(lastBody).contains("\"model\":\"mimo-v2.5-pro\"");
        assertThat(lastBody).contains("\"stream\":false");
        assertThat(lastBody).contains("\"max_completion_tokens\":512");
        assertThat(lastBody).contains("\"role\":\"user\"");
        assertThat(lastBody).contains("\"content\":\"hi\"");
    }

    @Test
    void normalizesSuccessfulCompletionWithUsage() {
        var completion = adapter.complete(context(), command()).block();

        assertThat(completion.usage()).isNotNull();
        assertThat(completion.usage().promptTokens()).isEqualTo(5);
        assertThat(completion.choices()).hasSize(1);
        assertThat(completion.choices().get(0).content()).isEqualTo("Hello");
        assertThat(completion.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(completion.upstreamId()).isEqualTo("chatcmpl_mock123");
    }

    @Test
    void missingUsageStaysAbsentNeverZero() {
        scenario = Scenario.NO_USAGE;

        var completion = adapter.complete(context(), command()).block();

        assertThat(completion.usage()).isNull();
    }

    @Test
    void provider4xxErrorsAreRedactedAndMappedToUpstreamFailed() {
        for (var status : List.of(Scenario.ERROR_400, Scenario.ERROR_401, Scenario.ERROR_403,
                Scenario.ERROR_413)) {
            scenario = status;
            assertThatThrownBy(() -> adapter.complete(context(), command()).block())
                    .isInstanceOf(GatewayErrorException.class)
                    .satisfies(ex -> {
                        assertThat(((GatewayErrorException) ex).code())
                                .isEqualTo(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED);
                        assertThat(((GatewayErrorException) ex).getMessage())
                                .doesNotContain("bad request body", "invalid api key", "forbidden",
                                        "too large");
                    });
        }
    }

    @Test
    void upstream429IssuesExactlyOneProviderRequestWithNoRetry() {
        scenario = Scenario.ERROR_429;

        assertThatThrownBy(() -> adapter.complete(context(), command()).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED));
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void upstream5xxIssuesExactlyOneProviderRequestWithNoRetry() {
        for (var status : List.of(Scenario.ERROR_500, Scenario.ERROR_503)) {
            scenario = status;
            REQUESTS.set(0);
            assertThatThrownBy(() -> adapter.complete(context(), command()).block())
                    .isInstanceOf(GatewayErrorException.class);
            assertThat(REQUESTS.get()).isEqualTo(1);
        }
    }

    @Test
    void responseHeaderTimeoutMapsToUpstreamTimeoutWithExactlyOneProviderRequest() {
        scenario = Scenario.DELAY;

        assertThatThrownBy(() -> adapter.complete(context(), command()).block())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT));
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    private static ChatCompletionCommand command() {
        return new ChatCompletionCommand(
                "default-chat",
                List.of(new ChatCompletionCommand.Message("user", "hi")),
                512,
                false);
    }

    private static ProviderCallContext context() {
        return new ProviderCallContext(
                "MIMO", 1L, 2L, "mimo-v2.5-pro", 3L, "USD",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "API_KEY", "api-key", SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static GatewayProperties properties() {
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(1000);
        properties.setHeaderTimeoutMs(300);
        properties.setMaxInMemoryBytes(16777216);
        return properties;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}