package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** M18 repair regressions: transport policy, redirect secret, discovery failure semantics. */
class M18TransportRegressionTest {

    @Test
    void openCodeUserAgentIsKnownWorkingSiblingPolicy() {
        assertEquals("opencode/1.18.21", ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT);
        assertEquals(ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT,
                ProviderTransportSupport.DEFAULT_PROBE_USER_AGENT);
    }

    @Test
    void customProviderNeverMasqueradesAsOpenCode() {
        assertEquals("opencode/1.18.21", ProviderTransportSupport.serverUserAgent(null, "OPENCODE_ZEN"));
        assertEquals(ProviderTransportSupport.NEUTRAL_PROVIDER_USER_AGENT,
                ProviderTransportSupport.serverUserAgent(null, "CUSTOM_OPENAI_COMPATIBLE"));
        assertEquals(ProviderTransportSupport.NEUTRAL_PROVIDER_USER_AGENT,
                ProviderTransportSupport.serverUserAgent("  ", "CUSTOM_OPENAI_COMPATIBLE"));
        assertEquals("Custom-Agent/9.9",
                ProviderTransportSupport.serverUserAgent("Custom-Agent/9.9", "CUSTOM_OPENAI_COMPATIBLE"));
    }

    /**
     * Cross-module UA contract: the Gateway adapter must carry the identical server-owned
     * OpenCode UA. Single source of truth is enforced by asserting the same literal on both
     * sides (plus a source-level presence check so a silent rename breaks loudly).
     */
    @Test
    void gatewayAdapterSharesServerOwnedOpenCodeUa() throws Exception {
        var adapter = java.nio.file.Path.of("../gateway/src/main/java/com/aicostops/gateway"
                + "/provider/opencode/OpenCodeZenChatAdapter.java");
        assertTrue(java.nio.file.Files.exists(adapter),
                () -> "Gateway adapter source moved; update the UA contract: " + adapter.toAbsolutePath());
        var source = java.nio.file.Files.readString(adapter);
        assertTrue(source.contains("\"opencode/1.18.21\""),
                () -> "Gateway OpenCode UA drifted from the frozen sibling policy");
        assertEquals("opencode/1.18.21", ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT);
    }

    @Test
    void sameOriginRequiresSchemeHostAndPort() {
        assertTrue(ProviderTransportSupport.sameOrigin(URI.create("https://a.example.com/v1"),
                URI.create("https://a.example.com/other")));
        assertFalse(ProviderTransportSupport.sameOrigin(URI.create("https://a.example.com/v1"),
                URI.create("https://b.example.com/v1")));
        assertFalse(ProviderTransportSupport.sameOrigin(URI.create("https://a.example.com/v1"),
                URI.create("http://a.example.com/v1")));
        assertFalse(ProviderTransportSupport.sameOrigin(URI.create("https://a.example.com:443/v1"),
                URI.create("https://a.example.com:8443/v1")));
        assertTrue(ProviderTransportSupport.sameOrigin(URI.create("https://a.example.com/v1"),
                URI.create("https://a.example.com:443/other")));
    }

    @Test
    void probeSameOriginMatchesTransport() {
        assertTrue(ModelProbeService.sameOrigin(URI.create("https://a.example.com/x"),
                URI.create("https://a.example.com/y")));
        assertFalse(ModelProbeService.sameOrigin(URI.create("https://a.example.com/x"),
                URI.create("https://evil.example.com/x")));
    }

    @Test
    void redirectResolutionRejectsMalformed() {
        var origin = URI.create("https://a.example.com/v1/chat/completions");
        var resolved = ModelProbeService.resolveRedirect(origin, origin.toString(), "/v2/models");
        assertEquals("a.example.com", resolved.getHost());
        assertThrows(IllegalArgumentException.class,
                () -> ModelProbeService.resolveRedirect(origin, origin.toString(), "http:///bad"));
    }
}
