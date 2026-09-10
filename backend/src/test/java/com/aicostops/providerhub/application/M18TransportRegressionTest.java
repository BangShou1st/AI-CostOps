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
