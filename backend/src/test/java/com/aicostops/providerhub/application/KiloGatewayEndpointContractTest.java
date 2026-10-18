package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Kilo Gateway endpoint contract (M18 V3; provider onboarded 2026-10-18).
 *
 * <p>Locks the server-owned Kilo Gateway base URL, paths, protocol, network policy and locked fields,
 * plus the exact URL composition (single {@code /v1}, no lost segment). Kilo is the second built-in
 * Provider after OpenCode Zen, so the template and its free-model manifest are pinned here the same
 * way {@link OpenCodeZenEndpointContractTest} pins the first.
 *
 * <p><b>Versioned decision (2026-10-18).</b> The base URL mirrors the Kilo shared library's
 * {@code kilo.gateway.base-url} default {@code https://ai-gateway.kilo.ai/api/v1}. Free classification
 * is delegated to the shared library's {@code FreeModelPolicy} so the Backend and the Gateway execute
 * one definition.
 */
class KiloGatewayEndpointContractTest {

    private static final String BASE = "https://ai-gateway.kilo.ai/api/v1";
    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";

    private final ProviderTemplateRegistry registry = new ProviderTemplateRegistry();
    private final KiloGatewayModelManifest manifest = new KiloGatewayModelManifest();

    @Test
    void builtInTemplateKeepsTheSanctionedBaseAndPaths() {
        var template = registry.require(ProviderTemplateRegistry.KILO_GATEWAY);
        assertEquals(BASE, ProviderTemplateRegistry.KILO_GATEWAY_BASE_URL);
        assertEquals(BASE, template.baseUrl());
        assertEquals("/chat/completions", template.completionPath());
        assertEquals("/models", template.modelsPath());
        assertEquals(ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS, template.protocolCode());
        assertEquals("BUILTIN", template.connectionKind());
        assertEquals("BEARER", template.authType());
        assertEquals("DIRECT_ONLY", template.networkPolicy());
    }

    @Test
    void completionAndModelsPathsComposeWithoutDuplicateV1OrLostSegment() {
        var template = registry.require(ProviderTemplateRegistry.KILO_GATEWAY);

        var completion = ProviderTransportSupport.joinBase(template.baseUrl(), template.completionPath());
        assertEquals("https://ai-gateway.kilo.ai/api/v1/chat/completions", completion);
        assertEquals(1, countOccurrences(completion, "/v1"));

        var models = ProviderTransportSupport.joinBase(template.baseUrl(), template.modelsPath());
        assertEquals("https://ai-gateway.kilo.ai/api/v1/models", models);
        assertEquals(1, countOccurrences(models, "/v1"));

        // A trailing slash on the base and a path without a leading slash must not double up.
        assertEquals("https://ai-gateway.kilo.ai/api/v1/chat/completions",
                ProviderTransportSupport.joinBase("https://ai-gateway.kilo.ai/api/v1/", "/chat/completions"));
        assertEquals("https://ai-gateway.kilo.ai/api/v1/chat/completions",
                ProviderTransportSupport.joinBase(template.baseUrl(), "chat/completions"));
    }

    @Test
    void builtInFieldsAreServerOwnedAndBrowserImmutable() {
        var template = registry.require(ProviderTemplateRegistry.KILO_GATEWAY);
        assertTrue(template.lockedFields().containsAll(Set.of(
                "baseUrl", "completionPath", "modelsPath", "userAgent", "authType", "networkPolicy")),
                "Built-in Kilo fields must stay server-owned and browser-immutable");
        assertTrue(registry.isBuiltIn(ProviderTemplateRegistry.KILO_GATEWAY));
        assertFalse(registry.isBuiltIn(ProviderTemplateRegistry.CUSTOM_OPENAI_COMPATIBLE));
    }

    @Test
    void kiloTrafficIsNeverDispatchedAsOpenCode() {
        // Kilo has no server-owned User-Agent; it must fall back to the neutral provider UA and never
        // masquerade as OpenCode.
        assertEquals(ProviderTransportSupport.NEUTRAL_PROVIDER_USER_AGENT,
                ProviderTransportSupport.serverUserAgent(null, ProviderTemplateRegistry.KILO_GATEWAY));
    }

    @Test
    void keylessCredentialIsAllowedOnlyForBuiltInFreeTiers() {
        assertTrue(ProviderTransportSupport.allowsKeylessCredential(ProviderTemplateRegistry.KILO_GATEWAY));
        assertTrue(ProviderTransportSupport.allowsKeylessCredential(ProviderTemplateRegistry.OPENCODE_ZEN));
        assertFalse(ProviderTransportSupport.allowsKeylessCredential(
                ProviderTemplateRegistry.CUSTOM_OPENAI_COMPATIBLE));
    }

    @Test
    void manifestDelegatesFreeAndProtocolClassificationToTheSharedPolicy() {
        // Kilo is an OpenAI-compatible gateway: every id is served through Chat Completions.
        assertEquals(KiloGatewayModelManifest.PROTOCOL_CHAT, manifest.protocolFor("anything-goes"));
        assertTrue(manifest.isChatCompatible("deepseek-v4-pro"));

        // Free classification is the shared library policy.
        assertTrue(manifest.isFreeEligible(FREE_MODEL));
        assertTrue(manifest.isFreeEligible("kilo-auto/free"));
        assertEquals(KiloGatewayModelManifest.PRICING_VERIFIED_FREE, manifest.pricingFor(FREE_MODEL));

        // Everything else fails closed: no probe/promotion/dispatch right through the built-in template.
        assertFalse(manifest.isFreeEligible("deepseek-v4-pro"));
        assertFalse(manifest.isFreeEligible(null));
        assertEquals(KiloGatewayModelManifest.PRICING_UNKNOWN, manifest.pricingFor("deepseek-v4-pro"));
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
