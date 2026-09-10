package com.aicostops.providerhub.application;
import java.util.Map;
import org.springframework.stereotype.Component;
/**
 * Server-owned versioned OpenCode model compatibility manifest (M18 P1-4, frozen M17 section 17).
 *
 * <p>OpenCode Zen publishes multiple endpoint families behind one live /models catalog.
 * The template-level protocol must never be mistaken for per-model compatibility.
 * Live /models proves availability only; protocol and pricing come from this controlled list.
 *
 * <p>Reviewed against the official OpenCode Zen endpoint table on 2026-09-11.
 * Chat Completions family is eligible for the bounded V3 Chat probe path; Responses,
 * Messages and Gemini-native families are UNSUPPORTED for V3 and stay AVAILABLE but
 * can never Chat probe/promote. Unknown future models fail closed as UNKNOWN.
 *
 * <p>Protocol and financial classification are independent: a -free suffix never implies
 * VERIFIED_FREE, and even VERIFIED_FREE never auto-creates production pricing.
 * Unknown live models stay AVAILABLE with UNKNOWN compatibility and cannot take the Chat path.
 * Custom OpenAI-compatible connections do not use this manifest.
 */
@Component
public class OpenCodeModelManifest {
    public static final String MANIFEST_VERSION = "2026-09-11-v2";
    public static final String VERIFIED_AT = "2026-09-11";
    public static final String SOURCE = "OpenCode Zen official endpoint table";
    public static final String SOURCE_REVISION = "2026-09-11";
    public static final String PROTOCOL_CHAT = "OPENAI_CHAT_COMPLETIONS";
    public static final String PROTOCOL_UNSUPPORTED = "UNSUPPORTED";
    public static final String PROTOCOL_UNKNOWN = "UNKNOWN";
    public static final String PRICING_VERIFIED_FREE = "VERIFIED_FREE";
    public static final String PRICING_PAID = "PAID";
    public static final String PRICING_UNKNOWN = "UNKNOWN";
    public record Entry(String protocol, String pricing) {
    }
    private static final java.util.Map<String, Entry> PRODUCTION_ENTRIES = java.util.Map.ofEntries(
            java.util.Map.entry("deepseek-v4-pro", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("deepseek-v4-flash", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("minimax-m3", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("minimax-m2.7", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("minimax-m2.5", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("glm-5.2", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("glm-5.1", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("glm-5", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("kimi-k2.5", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("kimi-k2.6", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("kimi-k2.7-code", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("kimi-k3", new Entry(PROTOCOL_CHAT, PRICING_PAID)),
            java.util.Map.entry("big-pickle", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("mimo-v2.5-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("laguna-s-2.1-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("ling-3.0-tiny-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("longcat-2.0-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("north-mini-code-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("nemotron-3-ultra-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("deepseek-v4-flash-free", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE)),
            java.util.Map.entry("gpt-5.6-sol", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.6-terra", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.6-luna", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.5-pro", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.4", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.4-pro", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.4-mini", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.4-nano", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.3-codex", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.3-codex-spark", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.2", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.2-codex", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.1", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.1-codex", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.1-codex-max", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5.1-codex-mini", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5-codex", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gpt-5-nano", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("grok-4.5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("grok-build-0.1", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-fable-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-opus-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-opus-4-8", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-opus-4-7", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-opus-4-6", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-opus-4-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-sonnet-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-sonnet-4-6", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-sonnet-4-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("claude-haiku-4-5", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("qwen3.7-max", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("qwen3.7-plus", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("qwen3.6-plus", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("qwen3.5-plus", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gemini-3.6-flash", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gemini-3.5-flash", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gemini-3.5-flash-lite", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gemini-3.1-pro", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)),
            java.util.Map.entry("gemini-3-flash", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN)));
    private final java.util.Map<String, Entry> entries;
    public OpenCodeModelManifest() {
        this.entries = PRODUCTION_ENTRIES;
    }
    private OpenCodeModelManifest(java.util.Map<String, Entry> entries, boolean testOnly) {
        this.entries = java.util.Map.copyOf(entries);
    }
    public static OpenCodeModelManifest testOnly(java.util.Map<String, Entry> entries) {
        return new OpenCodeModelManifest(entries, true);
    }
    public String version() { return MANIFEST_VERSION; }
    public String verifiedAt() { return VERIFIED_AT; }
    public String source() { return SOURCE; }
    public String sourceRevision() { return SOURCE_REVISION; }
    public Entry classify(String name) {
        if (name == null) return new Entry(PROTOCOL_UNKNOWN, PRICING_UNKNOWN);
        var k = name.strip();
        if (k.isEmpty()) return new Entry(PROTOCOL_UNKNOWN, PRICING_UNKNOWN);
        var f = entries.get(k);
        return f != null ? f : new Entry(PROTOCOL_UNKNOWN, PRICING_UNKNOWN);
    }
    public boolean isChatCompatible(String name) { return PROTOCOL_CHAT.equals(classify(name).protocol()); }
    public String protocolFor(String name) { return classify(name).protocol(); }
    public String pricingFor(String name) { return classify(name).pricing(); }
}
