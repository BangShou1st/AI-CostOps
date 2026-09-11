package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Production OpenCode compatibility metadata: real reviewed IDs, no fixture inference.
 */
class OpenCodeProductionManifestTest {

    private final OpenCodeModelManifest manifest = new OpenCodeModelManifest();

    @Test void manifestVersionIsV2WithProvenance() {
        assertEquals("2026-09-11-v2", manifest.version());
        assertEquals("2026-09-11-v2", OpenCodeModelManifest.MANIFEST_VERSION);
        assertEquals("2026-09-11", manifest.verifiedAt());
        assertEquals("OpenCode Zen official endpoint table", manifest.source());
        assertEquals("2026-09-11", manifest.sourceRevision());
    }

    @Test void realChatModelsAreCompatible() {
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("kimi-k2.6"));
        assertTrue(manifest.isChatCompatible("kimi-k2.6"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("deepseek-v4-pro"));
        assertTrue(manifest.isChatCompatible("deepseek-v4-pro"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("deepseek-v4-flash"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("minimax-m3"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("minimax-m2.7"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("minimax-m2.5"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("glm-5.2"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("glm-5.1"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("glm-5"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("kimi-k2.5"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("kimi-k2.7-code"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("kimi-k3"));
    }

    @Test void realFreeModelsAreVerifiedFreeWithoutSuffixInference() {
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("big-pickle"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("big-pickle"));
        assertTrue(manifest.isChatCompatible("big-pickle"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("mimo-v2.5-free"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", manifest.protocolFor("mimo-v2.5-free"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("laguna-s-2.1-free"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("ling-3.0-tiny-free"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("longcat-2.0-free"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("north-mini-code-free"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("nemotron-3-ultra-free"));
        assertEquals("VERIFIED_FREE", manifest.pricingFor("deepseek-v4-flash-free"));
    }

    @Test void responsesFamilyIsNotChatCompatible() {
        assertFalse(manifest.isChatCompatible("gpt-5.6-sol"));
        assertEquals("UNSUPPORTED", manifest.protocolFor("gpt-5.6-sol"));
        assertFalse(manifest.isChatCompatible("gpt-5.6-terra"));
        assertFalse(manifest.isChatCompatible("gpt-5.5"));
        assertFalse(manifest.isChatCompatible("grok-4.5"));
    }

    @Test void messagesFamilyIsNotChatCompatible() {
        assertFalse(manifest.isChatCompatible("claude-sonnet-5"));
        assertEquals("UNSUPPORTED", manifest.protocolFor("claude-sonnet-5"));
        assertFalse(manifest.isChatCompatible("claude-opus-5"));
        assertFalse(manifest.isChatCompatible("qwen3.7-max"));
        assertEquals("UNSUPPORTED", manifest.protocolFor("qwen3.7-max"));
        assertFalse(manifest.isChatCompatible("qwen3.5-plus"));
    }

    @Test void geminiNativeIsNotChatCompatible() {
        assertFalse(manifest.isChatCompatible("gemini-3.6-flash"));
        assertEquals("UNSUPPORTED", manifest.protocolFor("gemini-3.6-flash"));
        assertFalse(manifest.isChatCompatible("gemini-3-flash"));
    }

    @Test void unknownFutureModelFailsClosed() {
        assertEquals("UNKNOWN", manifest.protocolFor("future-unknown-model"));
        assertEquals("UNKNOWN", manifest.pricingFor("future-unknown-model"));
        assertFalse(manifest.isChatCompatible("future-unknown-model"));
        assertEquals("UNKNOWN", manifest.protocolFor("unknown-random-free"));
        assertEquals("UNKNOWN", manifest.pricingFor("unknown-random-free"));
        assertEquals("UNKNOWN", manifest.pricingFor("fake-name-free"));
        assertFalse(manifest.isChatCompatible("fake-name-free"));
    }

    @Test void fixtureNamesAreUnknownInProduction() {
        assertEquals("UNKNOWN", manifest.protocolFor("manifest-chat-fixture"));
        assertFalse(manifest.isChatCompatible("manifest-chat-fixture"));
        assertEquals("UNKNOWN", manifest.protocolFor("manifest-responses-fixture"));
    }
}
