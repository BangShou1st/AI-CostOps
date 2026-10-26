package com.aicostops.gateway.provider.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.routing.OpenCodeFreeOnlyAdmission;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase A offline test: verifies that the auto-selected relay genuinely redirects
 * outbound HTTP requests to the local loopback relay with the upstream target in x-relay-target,
 * and handles failures fail-closed without leaking credentials.
 */
class OpenCodeZenRelayIntegrationTest {

    private static final String SSE_STREAM_REPLY =
            "data: {\"id\":\"zen-1\",\"object\":\"chat.completion.chunk\",\"created\":1600000000,"
            + "\"model\":\"minimax-m3.1-flash-free\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"PONG\"},"
            + "\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"zen-1\",\"object\":\"chat.completion.chunk\",\"created\":1600000000,"
            + "\"model\":\"minimax-m3.1-flash-free\",\"choices\":[],"
            + "\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":10}}\n\n"
            + "data: [DONE]\n\n";

    private HttpServer relayServer;
    private int relayPort;
    private final List<String> relayReceivedTargets = new CopyOnWriteArrayList<>();
    private final List<String> relayReceivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger relayHits = new AtomicInteger();

    @AfterEach
    void stopRelay() {
        if (relayServer != null) {
            relayServer.stop(0);
        }
    }

    private void startMockBunRelay(int responseStatus, String responseBody, String contentType) throws IOException {
        relayServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        relayPort = relayServer.getAddress().getPort();
        relayServer.createContext("/", exchange -> {
            relayHits.incrementAndGet();
            var target = exchange.getRequestHeaders().getFirst(OpenCodeZenProtocolPolicy.RELAY_TARGET_HEADER);
            if (target == null || target.isBlank()) {
                // Official bun-relay behavior: returns 400 when x-relay-target is missing
                var errBytes = "{\"error\":\"x-relay-target header required\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, errBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errBytes);
                }
                return;
            }
            relayReceivedTargets.add(target);
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            relayReceivedBodies.add(body);

            byte[] outBytes = (responseBody != null ? responseBody : "").getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(responseStatus, outBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(outBytes);
            }
        });
        relayServer.start();
    }

    private ProviderCallContext sampleContext() {
        return new ProviderCallContext(
                "OPENCODE_ZEN", 1L, 2L, "minimax-m3.1-flash-free", 3L, "USD",
                "https://opencode.ai/inference/v1", "NONE", new byte[0],
                "route-decision-123", 7L,
                "/chat/completions", "OPENAI_CHAT_COMPLETIONS", "DIRECT_ONLY",
                OpenCodeZenChatAdapter.SERVER_USER_AGENT);
    }

    private ChatCompletionCommand sampleCommand() {
        return new ChatCompletionCommand(
                "minimax-m3.1-flash-free",
                List.of(new ChatCompletionCommand.Message("user", "Reply with exactly: PONG")),
                16, false);
    }

    @Test
    void relayUrlGenuinelyRedirectsHttpEgressAndPreservesWireToolsAndKeyOrder() throws Exception {
        startMockBunRelay(200, SSE_STREAM_REPLY, "text/event-stream");

        var factory = new OpenCodeZenRequestFactory(
                OpenCodeZenChatAdapter.SERVER_USER_AGENT, false, "http://127.0.0.1:" + relayPort);
        var resolverGroup = new OpenCodeZenAddressResolverGroup(
                com.aicostops.gateway.provider.PublicOnlyAddressResolverGroup.DnsResolver.system(), relayPort);
        var adapter = new OpenCodeZenChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard(), factory, OpenCodeFreeOnlyAdmission.production(),
                resolverGroup);

        var completion = adapter.complete(sampleContext(), sampleCommand()).block(Duration.ofSeconds(10));

        assertThat(completion).isNotNull();
        assertThat(completion.choices().get(0).content()).isEqualTo("PONG");
        assertThat(completion.usage()).isNotNull();
        assertThat(completion.usage().totalTokens()).isEqualTo(10);

        // Verification 1: Outbound HTTP request actually reached the Relay port
        assertThat(relayHits.get()).isGreaterThanOrEqualTo(1);
        // Verification 2: x-relay-target header points to canonical 2.x endpoint
        assertThat(relayReceivedTargets).contains("https://opencode.ai/inference/openai/v1/chat/completions");
        // Verification 3: Wire body carries stream=true, include_usage=true and reserved tools
        var sentBody = relayReceivedBodies.get(0);
        assertThat(sentBody).contains("\"stream\":true");
        assertThat(sentBody).contains("\"include_usage\":true");
        assertThat(sentBody).contains("\"tools\"");
        // Verification 4: Key order: model before messages before tools
        int idxModel = sentBody.indexOf("\"model\"");
        int idxMessages = sentBody.indexOf("\"messages\"");
        int idxTools = sentBody.indexOf("\"tools\"");
        assertThat(idxModel).isLessThan(idxMessages);
        assertThat(idxMessages).isLessThan(idxTools);
    }

    @Test
    void nonRelayPortReturning200IsNotTrustedAndFallsBackToDirectSafely() throws Exception {
        // Start an arbitrary mock HTTP server returning 200 on GET, pretending to be a service on that port
        relayServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        relayPort = relayServer.getAddress().getPort();
        relayServer.createContext("/", exchange -> {
            byte[] ok = "OK 200".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        relayServer.start();

        // Relay verification check fails
        assertThat(OpenCodeZenRelaySupport.isTrustedRelay("http://127.0.0.1:" + relayPort)).isFalse();
    }

    @Test
    void upstream429FromRelayFailsClosedWithoutMarkingSuccess() throws Exception {
        startMockBunRelay(429, "{\"error\":\"rate_limited\"}", "application/json");

        var factory = new OpenCodeZenRequestFactory(
                OpenCodeZenChatAdapter.SERVER_USER_AGENT, false, "http://127.0.0.1:" + relayPort);
        var resolverGroup = new OpenCodeZenAddressResolverGroup(
                com.aicostops.gateway.provider.PublicOnlyAddressResolverGroup.DnsResolver.system(), relayPort);
        var adapter = new OpenCodeZenChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard(), factory, OpenCodeFreeOnlyAdmission.production(),
                resolverGroup);

        assertThatThrownBy(() -> adapter.complete(sampleContext(), sampleCommand()).block(Duration.ofSeconds(5)))
                .isInstanceOf(ProviderExecutionException.class);
    }

    @Test
    void incompleteSseWithoutDoneMarkerFailsClosed() throws Exception {
        String truncatedSse = "data: {\"id\":\"zen-1\",\"object\":\"chat.completion.chunk\",\"created\":1600000000,"
                + "\"model\":\"minimax-m3.1-flash-free\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"PONG\"},"
                + "\"finish_reason\":null}]}\n\n";
        startMockBunRelay(200, truncatedSse, "text/event-stream");

        var factory = new OpenCodeZenRequestFactory(
                OpenCodeZenChatAdapter.SERVER_USER_AGENT, false, "http://127.0.0.1:" + relayPort);
        var resolverGroup = new OpenCodeZenAddressResolverGroup(
                com.aicostops.gateway.provider.PublicOnlyAddressResolverGroup.DnsResolver.system(), relayPort);
        var adapter = new OpenCodeZenChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard(), factory, OpenCodeFreeOnlyAdmission.production(),
                resolverGroup);

        assertThatThrownBy(() -> adapter.complete(sampleContext(), sampleCommand()).block(Duration.ofSeconds(5)))
                .isInstanceOf(ProviderExecutionException.class);
    }

    @Test
    void validateUpstreamTargetEnforcesHttpsAndOpencodeHost() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://opencode.ai/inference/openai/v1/chat/completions"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("http://opencode.ai/inference/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use https scheme");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://169.254.169.254/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must be strictly opencode.ai");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://attacker.com/steal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must be strictly opencode.ai");
    }

    @Test
    void adapterRejectsUntrustedBaseUrlBeforeNetworkCall() {
        var factory = new OpenCodeZenRequestFactory(
                OpenCodeZenChatAdapter.SERVER_USER_AGENT, false, "http://127.0.0.1:18992");
        var adapter = new OpenCodeZenChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard(), factory, OpenCodeFreeOnlyAdmission.production());

        var badContext = new ProviderCallContext(
                "OPENCODE_ZEN", 1L, 2L, "deepseek-v4.1-flash-free", 3L, "USD",
                "https://169.254.169.254/inference", "NONE", new byte[0],
                "route-decision-ssrf", 7L,
                "/chat/completions", "OPENAI_CHAT_COMPLETIONS", "DIRECT_ONLY",
                OpenCodeZenChatAdapter.SERVER_USER_AGENT);

        assertThatThrownBy(() -> adapter.complete(badContext, sampleCommand()).block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must be strictly opencode.ai");
    }

    @Test
    void arbitraryLocalPortIsBlockedByResolverGroup() throws Exception {
        var filtering = new OpenCodeZenAddressResolverGroup.FilteringResolver(
                io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE,
                com.aicostops.gateway.provider.PublicOnlyAddressResolverGroup.DnsResolver.system(),
                OpenCodeZenRelaySupport.DEFAULT_RELAY_PORT);

        // Resolving 127.0.0.1 on port 3306 (MySQL) must fail with UnknownHostException
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                filtering.resolveAll(new java.net.InetSocketAddress("127.0.0.1", 3306),
                        new io.netty.util.concurrent.DefaultPromise<>(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE)).get())
                .hasCauseInstanceOf(java.net.UnknownHostException.class);

        // Resolving 127.0.0.1 on trusted port 18992 must succeed
        var future = filtering.resolveAll(new java.net.InetSocketAddress("127.0.0.1", OpenCodeZenRelaySupport.DEFAULT_RELAY_PORT),
                new io.netty.util.concurrent.DefaultPromise<>(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE));
        assertThat(future.get()).isNotEmpty();
    }
}
