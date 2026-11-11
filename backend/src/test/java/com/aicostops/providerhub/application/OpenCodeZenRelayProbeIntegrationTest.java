package com.aicostops.providerhub.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.providerhub.domain.ProviderConnection;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase A offline integration test for Backend ModelProbeService with OpenCode Zen Relay.
 */
class OpenCodeZenRelayProbeIntegrationTest {

    private static final String VALID_PROBE_SSE =
            "data: {\"id\":\"probe-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"minimax-m3.1-flash-free\",\"choices\":[{\"index\":0,\"finish_reason\":null,"
            + "\"delta\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"}}]}\n\n"
            + "data: {\"id\":\"probe-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"minimax-m3.1-flash-free\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"delta\":{\"role\":null,\"content\":null}}]}\n\n"
            + "data: {\"id\":\"probe-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"minimax-m3.1-flash-free\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
            + "\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
            + "data: [DONE]\n\n";

    private HttpServer relayServer;
    private int relayPort;
    private final List<String> receivedTargets = new CopyOnWriteArrayList<>();
    private final AtomicInteger relayHits = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (relayServer != null) {
            relayServer.stop(0);
        }
    }

    private void startMockRelay(int statusCode, String body, String contentType) throws IOException {
        relayServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        relayPort = relayServer.getAddress().getPort();
        relayServer.createContext("/", exchange -> {
            relayHits.incrementAndGet();
            var target = exchange.getRequestHeaders().getFirst(OpenCodeZenProtocolPolicy.RELAY_TARGET_HEADER);
            if (target == null || target.isBlank()) {
                var err = "{\"error\":\"x-relay-target header required\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            receivedTargets.add(target);
            var resp = (body != null ? body : "").getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(statusCode, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        relayServer.start();
    }

    private ProviderConnection sampleProfile() {
        return new ProviderConnection(
                1L, 100L, 200L, 1, "BUILTIN", "OPENCODE_ZEN", "OPENAI_CHAT_COMPLETIONS",
                "https://opencode.ai/inference/v1", "/chat/completions",
                "/models", "BEARER", null,
                "DIRECT_ONLY", ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT,
                5000, 15000, "ACTIVE", 1L, java.time.Instant.now(), java.time.Instant.now(), null);
    }

    private ProviderControlPlaneTransport testTransport(int port) {
        return new ProviderControlPlaneTransport(
                CustomEndpointValidator.DnsResolver.system(),
                (addr, p) -> CustomEndpointValidator.isPublicUnicast(addr) || (addr.isLoopbackAddress() && p == port),
                true,
                port);
    }

    @Test
    void probeCorrectlyUsesRelayAndPassesOnValidUpstreamSse() throws Exception {
        startMockRelay(200, VALID_PROBE_SSE, "text/event-stream");

        // Create headers adapter with mock relay url
        var factory = new OpenCodeZenRequestFactory(
                ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT, false, "http://127.0.0.1:" + relayPort);
        var headersAdapter = new OpenCodeZenTransportHeaders(factory);

        var service = new ModelProbeService(
                null, null, null, null,
                new CustomEndpointValidator(),
                testTransport(relayPort),
                null, new ObjectMapper(), java.time.Clock.systemUTC(),
                null, new OpenCodeModelManifest(),
                new KiloGatewayModelManifest(),
                headersAdapter);

        var capabilities = new LinkedHashMap<String, String>();
        var outcome = service.probeOpenCodeZen(
                sampleProfile(), "minimax-m3.1-flash-free", null, null, capabilities);

        assertThat(relayHits.get()).isGreaterThanOrEqualTo(1);
        assertThat(receivedTargets).contains("https://opencode.ai/inference/openai/v1/chat/completions");
        assertThat(outcome.pass()).isTrue();
        assertThat(capabilities.get("CHAT_COMPLETIONS")).isEqualTo("VERIFIED");
        assertThat(capabilities.get("USAGE")).isEqualTo("VERIFIED");
        assertThat(capabilities.get("STRUCTURED_JSON")).isEqualTo("VERIFIED");
    }

    @Test
    void probeFailsClosedWhenRelayReturns429() throws Exception {
        startMockRelay(429, "{\"error\":\"rate_limited\"}", "application/json");

        var factory = new OpenCodeZenRequestFactory(
                ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT, false, "http://127.0.0.1:" + relayPort);
        var headersAdapter = new OpenCodeZenTransportHeaders(factory);

        var service = new ModelProbeService(
                null, null, null, null,
                new CustomEndpointValidator(),
                testTransport(relayPort),
                null, new ObjectMapper(), java.time.Clock.systemUTC(),
                null, new OpenCodeModelManifest(),
                new KiloGatewayModelManifest(),
                headersAdapter);

        var outcome = service.execute(sampleProfile(), "minimax-m3.1-flash-free");
        assertThat(outcome.pass()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void classifyOpenCodeZenStreamFailsClosedWithoutDone() {
        var capabilities = new LinkedHashMap<String, String>();
        var service = new ModelProbeService(
                null, null, null, null, null, null, null,
                new ObjectMapper(), null, null, new OpenCodeModelManifest());

        // Incomplete stream without [DONE]
        var outcome = service.classifyOpenCodeZenStream(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n", capabilities);
        assertThat(outcome.pass()).isFalse();
    }

    @Test
    void validateUpstreamTargetEnforcesHttpsAndOpencodeHostAndDefaultPort() {
        // Valid target passes
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://opencode.ai/inference/openai/v1/chat/completions"));

        // Non-https rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("http://opencode.ai/inference/openai/v1/chat/completions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use https scheme");

        // Non-opencode.ai host rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must be strictly opencode.ai");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://internal-db:3306/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host must be strictly opencode.ai");

        // Embedded credentials rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://user:pass@opencode.ai/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not embed credentials");

        // Non-standard port rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OpenCodeZenRelaySupport.validateUpstreamTarget("https://opencode.ai:8443/inference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port must be 443 or default");
    }

    @Test
    void transportRejectsLoopbackWithoutRelayTargetOrWithMaliciousTarget() {
        var transport = new ProviderControlPlaneTransport();

        // Non-18992 loopback port rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transport.post("http://127.0.0.1:3306/",
                        Map.of(OpenCodeZenProtocolPolicy.RELAY_TARGET_HEADER, "https://opencode.ai/inference/openai/v1/chat/completions"),
                        "{}", 1000, 1000))
                .isInstanceOf(ProviderControlPlaneTransport.TransportException.class)
                .hasMessageContaining("Loopback access is only permitted on trusted relay port 18992");

        // Loopback without x-relay-target header
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transport.post("http://127.0.0.1:18992/", Map.of(), "{}", 1000, 1000))
                .isInstanceOf(ProviderControlPlaneTransport.TransportException.class)
                .hasMessageContaining("requires x-relay-target");

        // Loopback with SSRF attempt in x-relay-target
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transport.post("http://127.0.0.1:18992/",
                        Map.of(OpenCodeZenProtocolPolicy.RELAY_TARGET_HEADER, "https://169.254.169.254/"),
                        "{}", 1000, 1000))
                .isInstanceOf(ProviderControlPlaneTransport.TransportException.class)
                .hasMessageContaining("Invalid relay target");
    }
}
