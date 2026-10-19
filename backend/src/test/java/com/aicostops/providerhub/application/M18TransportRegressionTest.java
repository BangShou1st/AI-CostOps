package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.*;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** M18 repair regressions: transport policy, redirect secret, discovery failure semantics. */
class M18TransportRegressionTest {

    @Test
    void openCodeUserAgentIsKnownWorkingSiblingPolicy() {
        assertEquals(OpenCodeZenProtocolPolicy.USER_AGENT, ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT);
        assertEquals(ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT,
                ProviderTransportSupport.DEFAULT_PROBE_USER_AGENT);
    }

    @Test
    void customProviderNeverMasqueradesAsOpenCode() {
        assertEquals(OpenCodeZenProtocolPolicy.USER_AGENT,
                ProviderTransportSupport.serverUserAgent(null, "OPENCODE_ZEN"));
        assertEquals(ProviderTransportSupport.NEUTRAL_PROVIDER_USER_AGENT,
                ProviderTransportSupport.serverUserAgent(null, "CUSTOM_OPENAI_COMPATIBLE"));
        assertEquals(ProviderTransportSupport.NEUTRAL_PROVIDER_USER_AGENT,
                ProviderTransportSupport.serverUserAgent("  ", "CUSTOM_OPENAI_COMPATIBLE"));
        assertEquals("Custom-Agent/9.9",
                ProviderTransportSupport.serverUserAgent("Custom-Agent/9.9", "CUSTOM_OPENAI_COMPATIBLE"));
    }

    /**
     * Cross-module UA contract: the Gateway adapter must carry the identical server-owned
     * OpenCode UA. The single source of truth is now the shared library's CLI identity policy; the
     * source-level check below fails loudly if the gateway reintroduces a divergent literal.
     */
    @Test
    void gatewayAdapterSharesServerOwnedOpenCodeUa() throws Exception {
        var adapter = java.nio.file.Path.of("../gateway/src/main/java/com/aicostops/gateway"
                + "/provider/opencode/OpenCodeZenChatAdapter.java");
        assertTrue(java.nio.file.Files.exists(adapter),
                () -> "Gateway adapter source moved; update the UA contract: " + adapter.toAbsolutePath());
        var source = java.nio.file.Files.readString(adapter);
        assertTrue(source.contains("OpenCodeZenProtocolPolicy.USER_AGENT"),
                () -> "Gateway OpenCode UA drifted from the shared identity policy");
        assertEquals(OpenCodeZenProtocolPolicy.USER_AGENT, ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT);
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
