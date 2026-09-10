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
 * <p>Protocol and financial classification are independent: a -free suffix never implies
 * VERIFIED_FREE, and even VERIFIED_FREE never auto-creates production pricing.
 * Unknown live models stay AVAILABLE with UNKNOWN compatibility and cannot take the Chat path.
 * Custom OpenAI-compatible connections do not use this manifest.
 */
@Component
public class OpenCodeModelManifest {
    public static final String MANIFEST_VERSION = "2026-09-11-v1";
    public static final String PROTOCOL_CHAT = "OPENAI_CHAT_COMPLETIONS";
    public static final String PROTOCOL_UNSUPPORTED = "UNSUPPORTED";
    public static final String PROTOCOL_UNKNOWN = "UNKNOWN";
    public static final String PRICING_VERIFIED_FREE = "VERIFIED_FREE";
    public static final String PRICING_PAID = "PAID";
    public static final String PRICING_UNKNOWN = "UNKNOWN";
    public record Entry(String protocol, String pricing) {
    }
    private final java.util.Map<String, Entry> entries = java.util.Map.of(
            "manifest-chat-fixture", new Entry(PROTOCOL_CHAT, PRICING_UNKNOWN),
            "manifest-chat-free-fixture", new Entry(PROTOCOL_CHAT, PRICING_VERIFIED_FREE),
            "manifest-responses-fixture", new Entry(PROTOCOL_UNSUPPORTED, PRICING_UNKNOWN));
    public String version() { return MANIFEST_VERSION; }
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
