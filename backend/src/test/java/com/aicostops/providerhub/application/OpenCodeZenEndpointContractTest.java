package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * OpenCode Zen endpoint contract (M18 V3 maintenance; endpoint re-pointed 2026-10-17).
 *
 * <p>Locks the server-owned OpenCode Zen base URL, paths, protocol, User-Agent and locked fields,
 * plus the exact URL composition (single {@code /v1}, no lost segment, no multi-concatenation).
 *
 * <p><b>Versioned decision (2026-10-17).</b> The built-in template no longer ships the legacy Zen
 * discovery origin; inference now uses the shared CLI inference base
 * {@code https://opencode.ai/inference/v1} and the shared User-Agent
 * ({@code OpenCodeZenProtocolPolicy.USER_AGENT}). The legacy {@code https://opencode.ai/zen/v1}
 * origin remains the discovery-only fallback and must never carry inference traffic. Evidence and
 * rationale: {@code docs/03-acceptance/2026-10-17-opencode-zen-inference-posture.md}.
 *
 * <p>The unprefixed CLI inference base is distinct from the API-family-prefixed Console routes
 * ({@code /inference/openai/v1/chat/completions}, {@code /inference/anthropic/v1/messages}, ...);
 * the anonymous {@code Bearer public} credential of any third-party relay shape is never adopted as
 * a template default.
 */
class OpenCodeZenEndpointContractTest {

    private static final String INFERENCE_BASE = "https://opencode.ai/inference/v1";

    private final ProviderTemplateRegistry registry = new ProviderTemplateRegistry();

    @Test
    void builtInTemplateKeepsTheSanctionedInferenceBaseAndPaths() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals(INFERENCE_BASE, ProviderTemplateRegistry.OPENCODE_ZEN_BASE_URL);
        assertEquals(INFERENCE_BASE, template.baseUrl());
        assertEquals("/chat/completions", template.completionPath());
        assertEquals("/models", template.modelsPath());
        assertEquals(ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS, template.protocolCode());
        assertEquals("BUILTIN", template.connectionKind());
        assertEquals("BEARER", template.authType());
        assertEquals("DIRECT_ONLY", template.networkPolicy());
    }

    @Test
    void theSanctionedInferenceBaseIsAdoptedWithoutRelayOrLoopbackShapes() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals(INFERENCE_BASE, template.baseUrl(),
                "The built-in template uses the shared CLI inference base (2026-10-17 decision)");
        assertFalse(template.baseUrl().startsWith("http://127.0.0.1"),
                "A loopback proxy base must never be a server-owned provider default");
        assertTrue(template.baseUrl().startsWith("https://opencode.ai/"),
                "OpenCode traffic must target the official https://opencode.ai origin");
        assertFalse(template.baseUrl().contains("/openai/"),
                "The built-in base is the unprefixed CLI inference base, not a console API-family route");
    }

    @Test
    void completionAndModelsPathsComposeWithoutDuplicateV1OrLostSegment() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);

        var completion = ProviderTransportSupport.joinBase(template.baseUrl(), template.completionPath());
        assertEquals("https://opencode.ai/inference/v1/chat/completions", completion);
        assertEquals(1, countOccurrences(completion, "/v1"),
                () -> "Duplicate or lost /v1 in resolved completion URL: " + completion);

        var models = ProviderTransportSupport.joinBase(template.baseUrl(), template.modelsPath());
        assertEquals("https://opencode.ai/inference/v1/models", models);
        assertEquals(1, countOccurrences(models, "/v1"),
                () -> "Duplicate or lost /v1 in resolved models URL: " + models);

        // Trailing slash on the base and a path without a leading slash must not double up.
        assertEquals("https://opencode.ai/inference/v1/chat/completions",
                ProviderTransportSupport.joinBase("https://opencode.ai/inference/v1/", "/chat/completions"));
        assertEquals("https://opencode.ai/inference/v1/chat/completions",
                ProviderTransportSupport.joinBase(template.baseUrl(), "chat/completions"));
    }

    @Test
    void serverOwnedUserAgentAndLockedFieldsStayEnforced() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals(OpenCodeZenProtocolPolicy.USER_AGENT, ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT);
        assertEquals(OpenCodeZenProtocolPolicy.USER_AGENT, template.userAgent());
        assertEquals(OpenCodeZenProtocolPolicy.USER_AGENT,
                ProviderTransportSupport.serverUserAgent(null, ProviderTemplateRegistry.OPENCODE_ZEN));
        assertTrue(template.lockedFields().containsAll(Set.of(
                "baseUrl", "completionPath", "modelsPath", "userAgent", "authType", "networkPolicy")),
                "Built-in OpenCode fields must stay server-owned and browser-immutable");
    }

    @Test
    void onlyChatCompletionsModelsShareTheChatAdapterFamily() {
        var manifest = new OpenCodeModelManifest();
        // Chat Completions family: dispatched through the Chat adapter.
        assertTrue(manifest.isChatCompatible("kimi-k2.6"));
        assertTrue(manifest.isChatCompatible("deepseek-v4-pro"));
        // Responses / Messages / Gemini-native families must never share Chat Completions.
        assertFalse(manifest.isChatCompatible("gpt-5.6-sol"));
        assertFalse(manifest.isChatCompatible("claude-sonnet-5"));
        assertFalse(manifest.isChatCompatible("gemini-3.6-flash"));
        // Unknown future models fail closed.
        assertEquals(OpenCodeModelManifest.PROTOCOL_UNKNOWN,
                manifest.protocolFor("opencode-future-model"));
        assertFalse(manifest.isChatCompatible("opencode-future-model"));
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
}
