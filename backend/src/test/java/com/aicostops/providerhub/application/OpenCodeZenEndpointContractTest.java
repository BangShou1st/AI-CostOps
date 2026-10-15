package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * OpenCode Zen endpoint contract (M18 V3 maintenance).
 *
 * <p>Locks the server-owned OpenCode Zen base URL, paths, protocol, User-Agent and locked
 * fields, plus the exact URL composition (single {@code /v1}, no lost segment, no
 * multi-concatenation). Verified against the official OpenCode Zen endpoint table
 * ({@code https://docs.opencode.ai/docs/zen/}) on 2026-10-16, where every Chat Completions
 * model maps to {@code https://opencode.ai/zen/v1/chat/completions} and the catalog lives at
 * {@code https://opencode.ai/zen/v1/models}.
 *
 * <p>The local reference proxy is an unofficial relay that forwards to a different,
 * proxy-only base ({@code https://opencode.ai/inference/v1}) with anonymous
 * {@code Bearer public} credentials. That base is deliberately NOT adopted here: the official
 * endpoint is {@code https://opencode.ai/zen/v1}, and the proxy-only relay must never become
 * the project's default template.
 */
class OpenCodeZenEndpointContractTest {

    private final ProviderTemplateRegistry registry = new ProviderTemplateRegistry();

    @Test
    void builtInTemplateKeepsTheOfficialZenBaseAndPaths() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals("https://opencode.ai/zen/v1", ProviderTemplateRegistry.OPENCODE_ZEN_BASE_URL);
        assertEquals("https://opencode.ai/zen/v1", template.baseUrl());
        assertEquals("/chat/completions", template.completionPath());
        assertEquals("/models", template.modelsPath());
        assertEquals(ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS, template.protocolCode());
        assertEquals("BUILTIN", template.connectionKind());
        assertEquals("BEARER", template.authType());
        assertEquals("DIRECT_ONLY", template.networkPolicy());
    }

    @Test
    void proxyOnlyRelayBaseIsNeverAdoptedAsTheDefaultTemplate() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertFalse(template.baseUrl().contains("inference"),
                "The unofficial /inference/v1 proxy relay must not become the OpenCode base URL");
        assertFalse(template.baseUrl().startsWith("http://127.0.0.1"),
                "A loopback proxy base must never be a server-owned provider default");
        assertTrue(template.baseUrl().startsWith("https://opencode.ai/"),
                "OpenCode traffic must target the official https://opencode.ai origin");
    }

    @Test
    void completionAndModelsPathsComposeWithoutDuplicateV1OrLostSegment() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);

        var completion = ProviderTransportSupport.joinBase(template.baseUrl(), template.completionPath());
        assertEquals("https://opencode.ai/zen/v1/chat/completions", completion);
        assertEquals(1, countOccurrences(completion, "/v1"),
                () -> "Duplicate or lost /v1 in resolved completion URL: " + completion);

        var models = ProviderTransportSupport.joinBase(template.baseUrl(), template.modelsPath());
        assertEquals("https://opencode.ai/zen/v1/models", models);
        assertEquals(1, countOccurrences(models, "/v1"),
                () -> "Duplicate or lost /v1 in resolved models URL: " + models);

        // Trailing slash on the base and a path without a leading slash must not double up.
        assertEquals("https://opencode.ai/zen/v1/chat/completions",
                ProviderTransportSupport.joinBase("https://opencode.ai/zen/v1/", "/chat/completions"));
        assertEquals("https://opencode.ai/zen/v1/chat/completions",
                ProviderTransportSupport.joinBase(template.baseUrl(), "chat/completions"));
    }

    @Test
    void serverOwnedUserAgentAndLockedFieldsStayEnforced() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals("opencode/1.18.21", ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT);
        assertEquals("opencode/1.18.21", template.userAgent());
        assertEquals("opencode/1.18.21",
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
