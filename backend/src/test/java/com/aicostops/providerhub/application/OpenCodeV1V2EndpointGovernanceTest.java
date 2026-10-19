package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * OpenCode endpoint governance.
 *
 * <p>OpenCode exposes coexisting first-party products on the same origin:
 *
 * <ul>
 *   <li><b>Legacy Zen discovery</b> — {@code https://opencode.ai/zen/v1} — now a <em>discovery-only
 *       fallback</em> for the catalog; inference traffic must never be sent here.</li>
 *   <li><b>CLI inference</b> — {@code https://opencode.ai/inference/v1} — the sanctioned inference
 *       base (shared {@code OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL}); catalog and inference live
 *       here.</li>
 *   <li><b>V2 Console</b> — {@code https://opencode.ai/inference} with API-family prefixes
 *       ({@code /openai/v1}, {@code /anthropic/v1}, ...) plus the unprefixed catalog at
 *       {@code /inference/v1/models}; a distinct product with its own service-account credentials.</li>
 * </ul>
 *
 * <p><b>Versioned decision (2026-10-17).</b> The built-in {@code OPENCODE_ZEN} template was
 * deliberately repointed from the legacy {@code zen/v1} origin to the shared CLI inference base
 * {@code https://opencode.ai/inference/v1}, together with the CLI identity/tool-envelope posture and
 * the shared User-Agent. That decision is recorded in
 * {@code docs/03-acceptance/2026-10-17-opencode-zen-inference-posture.md}. This test freezes the
 * resulting invariants so a later maintainer cannot silently drift the endpoint again:
 *
 * <ol>
 *   <li>the built-in OpenCode template uses the sanctioned {@code inference/v1} inference base;</li>
 *   <li>only that template may carry the inference base; no other server-owned template joins it;</li>
 *   <li>the API-family-prefixed Console routes remain a different shape from the unprefixed CLI
 *       inference base;</li>
 *   <li>adoption stays a <em>recorded decision</em>, not an ad-hoc half-migrated template code;</li>
 *   <li>OpenCode credentials stay a real {@code BEARER} secret; the reference proxy's anonymous
 *       {@code Bearer public} is never a template default.</li>
 * </ol>
 */
class OpenCodeV1V2EndpointGovernanceTest {

    /** Sanctioned CLI inference base now shipped by the built-in template. */
    private static final String INFERENCE_BASE = "https://opencode.ai/inference/v1";
    /** Legacy Zen origin, retained as a discovery-only fallback — never for inference. */
    private static final String LEGACY_ZEN_BASE = "https://opencode.ai/zen/v1";
    /** Official V2 Console inference base (paths carry the API-family prefix). */
    private static final String V2_CONSOLE_INFERENCE_BASE = "https://opencode.ai/inference";
    /** Officially documented V2 Console Chat Completions endpoint. */
    private static final String V2_CHAT_COMPLETIONS =
            "https://opencode.ai/inference/openai/v1/chat/completions";
    /** Officially documented V2 Console catalog endpoint (unprefixed route). */
    private static final String V2_MODELS = "https://opencode.ai/inference/v1/models";
    /** Legacy V1 Zen sibling routes, frozen by the earlier endpoint-table review. */
    private static final String LEGACY_ZEN_RESPONSES = "https://opencode.ai/zen/v1/responses";
    private static final String LEGACY_ZEN_MESSAGES = "https://opencode.ai/zen/v1/messages";

    private final ProviderTemplateRegistry registry = new ProviderTemplateRegistry();

    @Test
    void builtInTemplateUsesTheSanctionedCliInferenceBase() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals(INFERENCE_BASE, ProviderTemplateRegistry.OPENCODE_ZEN_BASE_URL);
        assertEquals(INFERENCE_BASE, template.baseUrl());
        assertEquals("/chat/completions", template.completionPath());
        assertEquals(ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS, template.protocolCode());
        assertEquals("DIRECT_ONLY", template.networkPolicy());
        // The legacy discovery origin must not be the shipped inference base any more.
        assertNotEquals(LEGACY_ZEN_BASE, template.baseUrl());
    }

    @Test
    void onlyTheBuiltInTemplateCarriesTheSanctionedInferenceBase() {
        var builtIn = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals(INFERENCE_BASE, builtIn.baseUrl());
        for (var template : registry.list()) {
            if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(template.code())) {
                continue;
            }
            var base = template.baseUrl();
            assertFalse(base != null && base.contains("/inference"),
                    () -> "Only the built-in OpenCode Zen template may adopt the inference base: "
                            + template.code() + " -> " + base);
        }
    }

    @Test
    void legacyAndConsoleInferenceFamiliesStayDistinctEndpointShapes() {
        // The products are genuinely different URL shapes; neither may be silently aliased to the
        // other. In particular the unprefixed CLI base is not the API-family-prefixed Console route.
        assertNotEquals(LEGACY_ZEN_BASE, INFERENCE_BASE);
        assertNotEquals(INFERENCE_BASE, V2_CHAT_COMPLETIONS);
        assertFalse(LEGACY_ZEN_BASE.startsWith(V2_CONSOLE_INFERENCE_BASE),
                "The legacy Zen origin must not be modelled as a Console sub-path");
        assertFalse(V2_CHAT_COMPLETIONS.startsWith(LEGACY_ZEN_BASE),
                "The Console Chat endpoint must not be modelled as a legacy Zen sub-path");

        // Legacy Zen keeps its own sibling routes; the Console carries an explicit API-family prefix.
        assertTrue(LEGACY_ZEN_RESPONSES.startsWith(LEGACY_ZEN_BASE + "/"));
        assertTrue(LEGACY_ZEN_MESSAGES.startsWith(LEGACY_ZEN_BASE + "/"));
        var consoleFamily = V2_CHAT_COMPLETIONS.substring(V2_CONSOLE_INFERENCE_BASE.length());
        assertTrue(consoleFamily.startsWith("/openai/v1/"),
                () -> "Console routes carry an API-family prefix, not a bare /v1 path: " + consoleFamily);
        assertTrue(V2_MODELS.startsWith(V2_CONSOLE_INFERENCE_BASE + "/v1/"),
                "The Console catalog is the only unprefixed Console route");
        assertFalse(V2_MODELS.contains("/openai/"),
                "The catalog lives outside the API-family prefixes");
        // The shipped base is the unprefixed CLI inference base, i.e. not an API-family Console route.
        assertFalse(INFERENCE_BASE.contains("/openai/"));
    }

    @Test
    void theInferenceAdoptionIsAnExplicitVersionedDecisionNotAnAdHocCode() {
        // The 2026-10-17 decision deliberately adopted the CLI inference base for the built-in
        // OPENCODE_ZEN template; it is recorded rather than arriving as a new unresolved template.
        for (var template : registry.list()) {
            var code = template.code();
            assertFalse(code.contains("INFERENCE") || code.contains("CONSOLE") || code.contains("V2"),
                    () -> "Inference adoption must stay a recorded decision, not an ad-hoc template: "
                            + code);
        }
        assertTrue(registry.known(ProviderTemplateRegistry.OPENCODE_ZEN));
        assertTrue(registry.known(ProviderTemplateRegistry.KILO_GATEWAY));
        assertEquals(3, registry.list().size(),
                "The built-in templates today are OpenCode Zen, Kilo Gateway and Custom OpenAI-compatible");
    }

    @Test
    void openCodeCredentialsStayRealSecretsAndNeverTheProxyAnonymousDefault() {
        for (var template : registry.list()) {
            if (!template.code().toUpperCase(java.util.Locale.ROOT).contains("OPENCODE")) {
                continue;
            }
            assertEquals("BEARER", template.authType(),
                    "OpenCode connections must keep a real Bearer secret");
            assertNotEquals("NONE", template.authType(),
                    "The reference proxy's anonymous Bearer public must never be a template default");
        }
    }
}
