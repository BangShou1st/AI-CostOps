package com.aicostops.providerhub.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Server-owned Provider Template defaults (M18 V3 Provider Hub).
 *
 * <p>Templates own safe defaults and field restrictions. Browser input may
 * never override locked template fields; violations are rejected, not merged.
 */
@Component
public class ProviderTemplateRegistry {

    public static final String OPENCODE_ZEN = "OPENCODE_ZEN";
    public static final String CUSTOM_OPENAI_COMPATIBLE = "CUSTOM_OPENAI_COMPATIBLE";
    public static final String PROTOCOL_OPENAI_CHAT_COMPLETIONS = "OPENAI_CHAT_COMPLETIONS";
    public static final String OPENCODE_ZEN_BASE_URL = "https://opencode.ai/zen/v1";
    /** Server-owned OpenCode Zen User-Agent. Inherits the known working sibling-project policy. */
    public static final String OPENCODE_ZEN_USER_AGENT = "opencode/1.18.21";

    private final Map<String, ProviderTemplate> templates = Map.of(
            OPENCODE_ZEN, new ProviderTemplate(
                    OPENCODE_ZEN, "OpenCode Zen", "BUILTIN",
                    PROTOCOL_OPENAI_CHAT_COMPLETIONS, OPENCODE_ZEN_BASE_URL,
                    "/chat/completions", "/models", "BEARER", null,
                    "DIRECT_ONLY", OPENCODE_ZEN_USER_AGENT, 5000, 60000,
                    Set.of("baseUrl", "networkPolicy", "userAgent", "authType",
                            "completionPath", "modelsPath")),
            CUSTOM_OPENAI_COMPATIBLE, new ProviderTemplate(
                    CUSTOM_OPENAI_COMPATIBLE, "Custom OpenAI-Compatible", "CUSTOM",
                    PROTOCOL_OPENAI_CHAT_COMPLETIONS, null,
                    "/chat/completions", "/models", "BEARER", null,
                    "DIRECT_PUBLIC_ONLY", null, 5000, 60000, Set.of("networkPolicy")));

    public List<ProviderTemplate> list() {
        return List.copyOf(templates.values());
    }

    public ProviderTemplate require(String templateCode) {
        var template = templates.get(Objects.requireNonNull(templateCode, "Template code is required"));
        if (template == null) {
            throw new IllegalArgumentException("Unknown provider template: " + templateCode);
        }
        return template;
    }

    public boolean known(String templateCode) {
        return templateCode != null && templates.containsKey(templateCode);
    }

    public record ProviderTemplate(
            String code,
            String name,
            String connectionKind,
            String protocolCode,
            String baseUrl,
            String completionPath,
            String modelsPath,
            String authType,
            String authHeaderName,
            String networkPolicy,
            String userAgent,
            int connectTimeoutMs,
            int responseTimeoutMs,
            Set<String> lockedFields) {
    }
}
