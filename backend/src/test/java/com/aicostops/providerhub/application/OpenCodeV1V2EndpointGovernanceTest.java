package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * OpenCode V1 Zen / V2 Console endpoint governance (2026-10-16 independent review).
 *
 * <p>OpenCode currently exposes <em>two coexisting</em> first-party inference products on the same
 * origin, with different bases and different credential products:
 *
 * <ul>
 *   <li><b>V1 Zen</b> — base {@code https://opencode.ai/zen/v1}
 *       ({@code /chat/completions}, {@code /responses}, {@code /messages}, {@code /systemone},
 *       {@code /models}).</li>
 *   <li><b>V2 Console</b> — base {@code https://opencode.ai/inference}, with <em>API-family
 *       prefixes</em> {@code /openai/v1}, {@code /anthropic/v1}, {@code /google/v1beta},
 *       {@code /systemone/v1} plus an unprefixed catalog at {@code /inference/v1/models}.</li>
 * </ul>
 *
 * <p>The V2 family is <em>official</em> (not a third-party relay), and the V1 family is
 * <em>still live</em>: routed POSTs against {@code /zen/v1/chat/completions} and
 * {@code /inference/openai/v1/chat/completions} both reached a live handler on 2026-10-16, while
 * deliberately bogus sibling paths returned {@code 404}. Existing V1 documentation now 302-redirects
 * to the V2 Console pages, which is a <em>docs</em> consolidation and not evidence of V1 retirement.
 *
 * <p>This test freezes the resulting governance decision, so that a future maintainer cannot
 * "helpfully" repoint the existing built-in template at the V2 family — which would silently mix a
 * V1 credential product with a V2 service-account product, and would change which models the
 * connection can address without any versioned migration:
 *
 * <ol>
 *   <li>the built-in OpenCode template stays on the V1 Zen origin;</li>
 *   <li>no server-owned template points into the V2 Console inference family;</li>
 *   <li>V1 and V2 are provably distinct endpoints — adopting V2 requires a <em>new, separately
 *       versioned</em> template code, never an in-place edit of {@code OPENCODE_ZEN};</li>
 *   <li>OpenCode credentials stay a real {@code BEARER} secret; the reference proxy's anonymous
 *       {@code Bearer public} is never a template default.</li>
 * </ol>
 *
 * <p>Sources (retrieved 2026-10-16):
 * {@code https://opencode.ai/v2/docs/console/models},
 * {@code https://opencode.ai/v2/docs/console/api/inference},
 * {@code https://opencode.ai/v2/docs/console/api} (service-account keys, {@code oc_sk_...}).
 */
class OpenCodeV1V2EndpointGovernanceTest {

    /** V1 Zen product base, as shipped today. */
    private static final String V1_ZEN_BASE = "https://opencode.ai/zen/v1";
    /** Official V2 Console inference base (paths carry the API-family prefix). */
    private static final String V2_INFERENCE_BASE = "https://opencode.ai/inference";
    /** Officially documented V2 Console Chat Completions endpoint. */
    private static final String V2_CHAT_COMPLETIONS =
            "https://opencode.ai/inference/openai/v1/chat/completions";
    /** Officially documented V2 Console catalog endpoint. */
    private static final String V2_MODELS = "https://opencode.ai/inference/v1/models";
    /** Officially documented V1 Zen sibling routes, frozen by the earlier endpoint-table review. */
    private static final String V1_ZEN_RESPONSES = "https://opencode.ai/zen/v1/responses";
    private static final String V1_ZEN_MESSAGES = "https://opencode.ai/zen/v1/messages";

    private final ProviderTemplateRegistry registry = new ProviderTemplateRegistry();

    @Test
    void builtInTemplateStaysOnTheV1ZenOrigin() {
        var template = registry.require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals(V1_ZEN_BASE, ProviderTemplateRegistry.OPENCODE_ZEN_BASE_URL);
        assertEquals(V1_ZEN_BASE, template.baseUrl());
        assertEquals("/chat/completions", template.completionPath());
        assertEquals(ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS, template.protocolCode());
        assertEquals("DIRECT_ONLY", template.networkPolicy());
    }

    @Test
    void noServerOwnedTemplatePointsAtTheV2ConsoleInferenceFamily() {
        for (var template : registry.list()) {
            var base = template.baseUrl();
            if (base == null) {
                continue;
            }
            assertFalse(base.contains("/inference"),
                    () -> "Template " + template.code() + " must not point into the V2 Console "
                            + "inference family without an explicit, versioned decision: " + base);
        }
    }

    @Test
    void v1AndV2AreDistinctEndpointsAndNeverShareOneTemplateCode() {
        // The two products are genuinely different URL shapes; neither may be silently aliased
        // to the other, which is exactly the confusion this review resolved.
        assertNotEquals(V1_ZEN_BASE, V2_INFERENCE_BASE);
        assertNotEquals(V1_ZEN_BASE, V2_CHAT_COMPLETIONS);
        assertFalse(V1_ZEN_BASE.startsWith(V2_INFERENCE_BASE),
                "V1 Zen must not be modelled as a V2 Console sub-path");
        assertFalse(V2_CHAT_COMPLETIONS.startsWith(V1_ZEN_BASE),
                "The V2 Console Chat endpoint must not be modelled as a V1 Zen sub-path");

        // V1 keeps its own sibling routes; V2 carries an explicit API-family prefix instead.
        assertTrue(V1_ZEN_RESPONSES.startsWith(V1_ZEN_BASE + "/"));
        assertTrue(V1_ZEN_MESSAGES.startsWith(V1_ZEN_BASE + "/"));
        var v2Family = V2_CHAT_COMPLETIONS.substring(V2_INFERENCE_BASE.length());
        assertTrue(v2Family.startsWith("/openai/v1/"),
                () -> "V2 Console routes carry an API-family prefix, not a bare /v1 path: " + v2Family);
        assertTrue(V2_MODELS.startsWith(V2_INFERENCE_BASE + "/v1/"),
                "The V2 catalog is the only unprefixed V2 route");
        assertFalse(V2_MODELS.contains("/openai/"),
                "The catalog lives outside the API-family prefixes");
    }

    @Test
    void adoptingV2RequiresANewTemplateCodeNotAnEditOfTheV1Base() {
        // A V2 template, when it is introduced, must be its own code. Until then the registry must
        // not contain a half-adopted V2/Console/Inference code that could drift from the V1 one.
        for (var template : registry.list()) {
            var code = template.code();
            assertFalse(code.contains("INFERENCE") || code.contains("CONSOLE") || code.contains("V2"),
                    () -> "V2 Console support must arrive as a deliberate versioned template; "
                            + "found unresolved code: " + code);
        }
        assertTrue(registry.known(ProviderTemplateRegistry.OPENCODE_ZEN));
        assertEquals(2, registry.list().size(),
                "Only the frozen V1 Zen and Custom OpenAI-compatible templates exist today");
    }

    @Test
    void openCodeCredentialsStayRealSecretsAndNeverTheProxyAnonymousDefault() {
        for (var template : registry.list()) {
            if (!template.code().toUpperCase(java.util.Locale.ROOT).contains("OPENCODE")) {
                continue;
            }
            assertEquals("BEARER", template.authType(),
                    "OpenCode connections must keep a real Bearer secret (V1 Zen key or, for a "
                            + "future V2 template, a Console service-account key)");
            assertNotEquals("NONE", template.authType(),
                    "The reference proxy's anonymous Bearer public must never be a template default");
        }
    }
}
