package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * OpenCode manifest drift against the official V1 Zen catalog (M-2, reviewed 2026-10-16).
 *
 * <p>The production manifest is a <em>server-owned controlled list</em>: the live
 * {@code GET /zen/v1/models} catalog proves availability only, while protocol family and pricing
 * classification must each be traceable to a first-party OpenCode product source. This test pins
 * the drift correction decided in
 * {@code docs/03-acceptance/2026-10-16-opencode-manifest-drift-repair.md}.
 *
 * <p>Evidence retrieved 2026-10-16 (Asia/Shanghai):
 *
 * <ul>
 *   <li>live V1 Zen catalog {@code https://opencode.ai/zen/v1/models} — 86 ids, byte-identical to
 *       the V2 Console catalog {@code https://opencode.ai/inference/v1/models};</li>
 *   <li>official V1 Zen endpoint + pricing table, revision
 *       {@code 2026-10-11T17:04:23Z} (the V1 docs URL now 302-redirects to the V2 Console docs,
 *       which is a docs consolidation and not endpoint retirement);</li>
 *   <li>official V2 Console docs {@code https://opencode.ai/v2/docs/console/models} used only as
 *       corroboration, never as the basis for a V1 classification.</li>
 * </ul>
 *
 * <p>Requirement separation under test: a model that the V2 Console lists as <em>free</em> must not
 * become {@code VERIFIED_FREE} for the V1 Zen template, and a model whose protocol family is only
 * attested by the V2 Console must not become Chat-compatible.
 */
class OpenCodeManifestDriftTest {

    /**
     * Models newly served by V1 Zen that carry both V1 evidence classes: an official
     * {@code /zen/v1/chat/completions} endpoint row and an official V1 pricing-table row.
     */
    private static final List<String> NEW_V1_CHAT_PAID = List.of(
            "deepseek-v4.1-flash",
            "glm-5.3",
            "glm-5.3-flash",
            "mistral-large-4",
            "qwen3.8-max");

    /** Same dual V1 evidence, but the official V1 pricing table lists them as Free. */
    private static final List<String> NEW_V1_CHAT_VERIFIED_FREE = List.of(
            "beam-free", "big-pickle", "deepseek-v4.1-flash-free", "glm-5.3-flash-free",
            "longcat-2.5-preview-free", "mimo-v2.6-flash-free", "minimax-m3.1-flash-free",
            "nemotron-3.5-lightning-free", "nemotron-3.5-super-vl-free");

    /**
     * Unreviewed ids and unimplemented protocol families remain unknown. The five newly reviewed
     * free Chat ids have moved to NEW_V1_CHAT_VERIFIED_FREE for the v1.4.2 policy revision.
     */
    private static final List<String> CONSOLE_ONLY_OR_UNIMPLEMENTED_FAMILY_MUST_STAY_UNKNOWN = List.of(
            "future-deepseek-free",
            "cat-jam-free",
            "muse-spark-1.2-contributor-free",
            "future-nemotron-free",
            "claude-haiku-5-5",
            "claude-opus-5-5",
            "claude-sonnet-5-5",
            "gemini-4-argon",
            "gpt-6-luna",
            "grok-4.7",
            "qwen3.8-flash");

    /**
     * Free Chat ids that carry official V1 evidence but are <em>not currently served</em> by the V1
     * catalog must stay unclassified. The controlled manifest admits only what the live V1 Zen
     * template can actually address today; a delisted id is re-admitted by the same dual-evidence
     * rule once it reappears in the catalog.
     */
    private static final List<String> DOC_EVIDENCED_BUT_DELISTED = List.of(
            "deepseek-v4-flash-vision-exp",
            "exo-free",
            "ling-3.0-flash-fin-free",
            "ling-3.1-flash-free",
            "step-5-preview-free");

    private final OpenCodeModelManifest manifest = new OpenCodeModelManifest();

    @Test
    void manifestRecordsTheDriftReviewProvenance() {
        assertEquals("2026-10-21-v2", manifest.version());
        assertEquals("2026-10-21-v2", OpenCodeModelManifest.MANIFEST_VERSION);
        assertEquals("2026-10-21", manifest.verifiedAt());
        assertEquals("2026-10-21", manifest.sourceRevision());
        assertTrue(manifest.source().contains("Zen"),
                () -> "Source must name the V1 Zen product, not the V2 Console: " + manifest.source());
    }

    @Test
    void newlyServedV1ChatModelsBecomeChatCompatible() {
        for (var model : NEW_V1_CHAT_PAID) {
            assertEquals(OpenCodeModelManifest.PROTOCOL_CHAT, manifest.protocolFor(model),
                    () -> "Official V1 Zen endpoint table serves " + model
                            + " at /zen/v1/chat/completions, so it must be Chat classified");
            assertTrue(manifest.isChatCompatible(model), model);
        }
        for (var model : NEW_V1_CHAT_VERIFIED_FREE) {
            assertEquals(OpenCodeModelManifest.PROTOCOL_CHAT, manifest.protocolFor(model),
                    () -> "Official V1 Zen endpoint table serves " + model
                            + " at /zen/v1/chat/completions, so it must be Chat classified");
            assertTrue(manifest.isChatCompatible(model), model);
        }
    }

    @Test
    void newlyServedChatPricingFollowsTheV1ZenPricingTable() {
        for (var model : NEW_V1_CHAT_PAID) {
            assertEquals(OpenCodeModelManifest.PRICING_PAID, manifest.pricingFor(model),
                    () -> "Official V1 Zen pricing table prices " + model
                            + "; it must never be classified free");
        }
        for (var model : NEW_V1_CHAT_VERIFIED_FREE) {
            assertEquals(OpenCodeModelManifest.PRICING_VERIFIED_FREE, manifest.pricingFor(model),
                    () -> "Official V1 Zen pricing table lists " + model + " as Free");
        }
    }

    @Test
    void consoleOnlyOrUnimplementedFamilyModelsStayUnknownAndNeverChatCompatible() {
        for (var model : CONSOLE_ONLY_OR_UNIMPLEMENTED_FAMILY_MUST_STAY_UNKNOWN) {
            assertEquals(OpenCodeModelManifest.PROTOCOL_UNKNOWN, manifest.protocolFor(model),
                    () -> "No V1 Zen Chat attestation for " + model + ": must fail closed");
            assertEquals(OpenCodeModelManifest.PRICING_UNKNOWN, manifest.pricingFor(model),
                    () -> "No V1 Zen pricing attestation for " + model + ": must fail closed");
            assertFalse(manifest.isChatCompatible(model), model);
        }
    }

    /**
     * Every {@code VERIFIED_FREE} entry must be affirmatively sourced. A {@code -free} suffix is
     * never sufficient, and a V2 Console "Free" row is never sufficient for a V1 Zen classification.
     */
    @Test
    void verifiedFreeEntriesAreNeverSuffixInferredAndNeverConsoleInferred() {
        // Suffix alone never imprints a classification.
        for (var invented : List.of(
                "totally-new-model-free",
                "future-deepseek-free",
                "glm-5.3-free",
                "qwen3.8-max-free")) {
            assertEquals(OpenCodeModelManifest.PRICING_UNKNOWN, manifest.pricingFor(invented), invented);
            assertFalse(manifest.isChatCompatible(invented), invented);
        }
        // The Console free list must not leak into the V1 template.
        for (var consoleFree : List.of(
                "future-deepseek-free",
                "cat-jam-free",
                "muse-spark-1.2-contributor-free",
                "future-nemotron-free")) {
            assertFalse(OpenCodeModelManifest.PRICING_VERIFIED_FREE.equals(
                            manifest.pricingFor(consoleFree)),
                    () -> consoleFree + " is free on the V2 Console only; V1 Zen must stay UNKNOWN");
        }
    }

    /**
     * Retired free chat ids stay documented (no lineage deletion) so a re-listed id keeps its
     * reviewed classification, while still never creating production pricing on their own.
     */
    @Test
    void retiredFreeChatIdsStayDocumentedWithoutCreatingPricing() {
        for (var retired : List.of(
                "deepseek-v4-flash-free",
                "laguna-s-2.1-free",
                "ling-3.0-tiny-free",
                "longcat-2.0-free",
                "north-mini-code-free")) {
            assertEquals(OpenCodeModelManifest.PROTOCOL_CHAT, manifest.protocolFor(retired), retired);
            assertEquals(OpenCodeModelManifest.PRICING_VERIFIED_FREE, manifest.pricingFor(retired), retired);
        }
    }

    /** Paid chat ids that are no longer served keep their reviewed paid classification. */
    @Test
    void delistedDocEvidencedChatIdsAreNotAdmittedAheadOfTheCatalog() {
        // Rule 3 of the drift policy: official V1 evidence alone is not enough to widen admission
        // for an id the V1 Zen template cannot address today. These must stay fail closed until the
        // catalog lists them again, at which point the same dual-evidence rule admits them.
        for (var delisted : DOC_EVIDENCED_BUT_DELISTED) {
            assertEquals(OpenCodeModelManifest.PROTOCOL_UNKNOWN, manifest.protocolFor(delisted),
                    () -> delisted + " has V1 evidence but is not currently served by V1 Zen;"
                            + " admission must not be widened ahead of the live catalog");
            assertEquals(OpenCodeModelManifest.PRICING_UNKNOWN, manifest.pricingFor(delisted), delisted);
            assertFalse(manifest.isChatCompatible(delisted), delisted);
        }
    }

    @Test
    void nonChatFamiliesAreNeverPromotedIntoChatByDriftRepair() {
        for (var unsupported : List.of(
                "gpt-5.6-sol",
                "claude-sonnet-5",
                "gemini-3.6-flash",
                "grok-4.6",
                "muse-spark-1.3",
                "qwen3.7-max")) {
            assertFalse(manifest.isChatCompatible(unsupported), unsupported);
        }
    }
}
