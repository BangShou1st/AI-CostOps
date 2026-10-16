package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * OPENCODE_ZEN FREE_ONLY admission for the controlled manifest (2026-10-16).
 *
 * <p>The built-in V1 Zen template is admitted for <em>free</em> dispatch only. This test pins the
 * exact admission rule the Backend probe and promotion gates must apply, and it separates three
 * concerns the earlier manifest work deliberately kept distinct:
 *
 * <ol>
 *   <li><b>classification</b> — {@code classify()} keeps the reviewed protocol/pricing metadata of
 *       every id ever reviewed, including historical {@code PAID};</li>
 *   <li><b>free eligibility</b> — {@code isFreeEligible()} admits an id only when it is a Chat
 *       Completions model, its official V1 Zen pricing row says Free, and it is still served by the
 *       V1 Zen catalog as of the reviewed revision;</li>
 *   <li><b>lineage</b> — a delisted or paid id is never deleted and never rewritten, it simply
 *       gains no probe or routing right.</li>
 * </ol>
 *
 * <p>Evidence: official V1 Zen endpoint + pricing tables, revision {@code 2026-10-11T17:04:23Z},
 * and the live {@code https://opencode.ai/zen/v1/models} catalog retrieved 2026-10-16
 * (86 ids, SHA-256 {@code 6a4fc13e81fd7a7905c5934cef9781e39255a9aa93e2da406fc4f02b682e2685}).
 * See {@code docs/03-acceptance/2026-10-16-opencode-free-only-admission.md}.
 */
class OpenCodeFreeOnlyAdmissionTest {

    /**
     * Exactly the V1 Zen Chat Completions models whose official V1 pricing row is Free
     * <em>and</em> which the live V1 catalog still serves. These — and only these — may be probed
     * and routed for the built-in OpenCode Zen template.
     */
    private static final List<String> ADMITTED_FREE_CHAT = List.of(
            "big-pickle",
            "longcat-2.5-preview-free",
            "mimo-v2.6-flash-free",
            "nemotron-3.5-lightning-free",
            "space-bunny-free");

    /**
     * Reviewed Free Chat ids that the V1 catalog no longer serves. Lineage is preserved and the
     * historical {@code VERIFIED_FREE} label stays, but a delisted id must never be dispatched: the
     * V1 template cannot address it, and a de-listing may precede a retirement.
     */
    private static final List<String> DELISTED_FREE_CHAT_NOT_ADMITTED = List.of(
            "mimo-v2.5-free",
            "laguna-s-2.1-free",
            "ling-3.0-tiny-free",
            "longcat-2.0-free",
            "north-mini-code-free",
            "nemotron-3-ultra-free",
            "deepseek-v4-flash-free");

    /** Reviewed paid Chat ids: metadata is retained, admission is refused. */
    private static final List<String> PAID_CHAT_NOT_ADMITTED = List.of(
            "deepseek-v4-pro",
            "deepseek-v4-flash",
            "minimax-m3",
            "glm-5",
            "kimi-k2.6",
            "deepseek-v4.1-flash",
            "glm-5.3",
            "glm-5.3-flash",
            "mistral-large-4",
            "qwen3.8-max");

    private final OpenCodeModelManifest manifest = new OpenCodeModelManifest();

    @Test
    void onlyEvidencedAndStillServedFreeChatModelsAreAdmitted() {
        for (var model : ADMITTED_FREE_CHAT) {
            assertTrue(manifest.isFreeEligible(model),
                    () -> model + " has an official V1 Zen Free pricing row and is still served:"
                            + " it must be admitted for free dispatch");
            assertTrue(manifest.isChatCompatible(model), model);
            assertEquals(OpenCodeModelManifest.PRICING_VERIFIED_FREE, manifest.pricingFor(model), model);
        }
    }

    @Test
    void paidChatModelsAreNeverFreeAdmitted() {
        for (var model : PAID_CHAT_NOT_ADMITTED) {
            assertEquals(OpenCodeModelManifest.PRICING_PAID, manifest.pricingFor(model),
                    () -> model + " must keep its reviewed PAID classification as metadata");
            assertFalse(manifest.isFreeEligible(model),
                    () -> model + " is PAID on the V1 Zen pricing table; it must never enter the"
                            + " free calling flow");
        }
    }

    /**
     * The distinction under test: a PAID model may still be Chat classified, so a protocol-only
     * gate would admit it. Free admission must therefore be a strictly stronger predicate.
     */
    @Test
    void chatCompatibilityAloneIsNeverSufficientForFreeAdmission() {
        for (var model : PAID_CHAT_NOT_ADMITTED) {
            assertTrue(manifest.isChatCompatible(model), model);
            assertFalse(manifest.isFreeEligible(model), model);
        }
        for (var model : DELISTED_FREE_CHAT_NOT_ADMITTED) {
            assertTrue(manifest.isChatCompatible(model), model);
            assertFalse(manifest.isFreeEligible(model), model);
        }
    }

    @Test
    void delistedFreeChatModelsKeepLineageButLoseAdmission() {
        for (var model : DELISTED_FREE_CHAT_NOT_ADMITTED) {
            assertEquals(OpenCodeModelManifest.PROTOCOL_CHAT, manifest.protocolFor(model),
                    () -> model + " must keep its reviewed classification (lineage is never deleted)");
            assertEquals(OpenCodeModelManifest.PRICING_VERIFIED_FREE, manifest.pricingFor(model), model);
            assertFalse(manifest.isFreeEligible(model),
                    () -> model + " is no longer served by the V1 catalog: admission must fail closed");
        }
    }

    @Test
    void unknownUnsupportedAndInventedIdsFailClosed() {
        for (var model : List.of(
                "gpt-5.6-sol",
                "claude-sonnet-5",
                "gemini-3.6-flash",
                "qwen3.7-max",
                "deepseek-v4.1-flash-free",
                "cat-jam-free",
                "nemotron-3.5-super-vl-free",
                "muse-spark-1.2-contributor-free",
                "exo-free",
                "step-5-preview-free",
                "ling-3.1-flash-free",
                "totally-new-model-free",
                "glm-5.3-free")) {
            assertFalse(manifest.isFreeEligible(model),
                    () -> model + " has no confirmed, currently valid VERIFIED_FREE Chat evidence;"
                            + " it must fail closed");
        }
        assertFalse(manifest.isFreeEligible(null));
        assertFalse(manifest.isFreeEligible(""));
        assertFalse(manifest.isFreeEligible("   "));
    }

    /**
     * Requirement: a {@code -free} suffix is never evidence, and a reviewed classification is never
     * rewritten. A paid id must stay {@code PAID} after the FREE_ONLY round, not become
     * {@code VERIFIED_FREE}.
     */
    @Test
    void freeOnlyAdmissionNeverRewritesPaidIntoVerifiedFree() {
        for (var model : PAID_CHAT_NOT_ADMITTED) {
            assertFalse(OpenCodeModelManifest.PRICING_VERIFIED_FREE.equals(manifest.pricingFor(model)),
                    () -> model + " must never be rewritten from PAID to VERIFIED_FREE");
        }
        for (var invented : List.of("deepseek-v4.1-flash-free", "qwen3.8-max-free", "glm-5.3-free")) {
            assertFalse(OpenCodeModelManifest.PRICING_VERIFIED_FREE.equals(manifest.pricingFor(invented)),
                    () -> invented + " must never be inferred free from its suffix");
            assertFalse(manifest.isFreeEligible(invented), invented);
        }
    }

    /** The admission policy is versioned so a rollout is traceable to one reviewed revision. */
    @Test
    void freeAdmissionPolicyIsPinnedAndTraceable() {
        assertEquals("2026-10-16-v1", OpenCodeModelManifest.FREE_ADMISSION_POLICY_VERSION);
        assertEquals(manifest.version(), OpenCodeModelManifest.FREE_ADMISSION_POLICY_VERSION,
                "the free admission policy and the manifest revision must advance together");
        assertEquals("2026-10-16", manifest.verifiedAt());
    }
}
