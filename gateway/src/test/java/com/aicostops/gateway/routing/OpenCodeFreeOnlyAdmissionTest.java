package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Independent Gateway-side OpenCode Zen free admission (2026-10-16).
 *
 * <p>This is the second, deliberately redundant barrier: a model that was once promoted, that has a
 * Pricing Version, or that sits on a routing policy activated before the FREE_ONLY round must still
 * never reach Provider HTTP I/O. The Browser/Control Plane is not consulted and the Backend gates
 * are not trusted here — the Gateway re-derives admission from its own server-owned list.
 */
class OpenCodeFreeOnlyAdmissionTest {

    private final OpenCodeFreeOnlyAdmission admission = OpenCodeFreeOnlyAdmission.production();

    @Test
    void admittedFreeChatModelsStayDispatchable() {
        for (var model : OpenCodeFreeOnlyAdmission.ADMITTED_FREE_CHAT_MODELS) {
            assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, model))
                    .as("%s has confirmed V1 Zen Free evidence and must stay callable", model)
                    .isTrue();
        }
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER,
                OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "deepseek-v4-pro",
            "deepseek-v4.1-flash",
            "glm-5.3",
            "glm-5.3-flash",
            "mistral-large-4",
            "qwen3.8-max",
            "minimax-m3",
            "kimi-k2.6"})
    void paidOpenCodeModelsAreNeverAdmittedEvenWithPricingAndRouting(String model) {
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, model))
                .as("%s is PAID on the V1 Zen pricing table", model)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mimo-v2.5-free",
            "laguna-s-2.1-free",
            "ling-3.0-tiny-free",
            "longcat-2.0-free",
            "north-mini-code-free",
            "nemotron-3-ultra-free",
            "deepseek-v4-flash-free",
            "deepseek-v4.1-flash-free",
            "cat-jam-free",
            "exo-free",
            "step-5-preview-free",
            "gpt-5.6-sol",
            "claude-sonnet-5",
            "gemini-3.6-flash",
            "future-unknown-model",
            "unknown-random-free"})
    void unknownUnsupportedDelistedAndConsoleOnlyModelsFailClosed(String model) {
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, model))
                .as("%s has no confirmed, currently valid free evidence", model)
                .isFalse();
    }

    @Test
    void suffixAndWhitespaceGlobalsNeverImplyAdmission() {
        for (var invented : List.of("anything-free", "glm-5.3-free", "qwen3.8-max-free", "GLM-5.3-FREE")) {
            assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, invented))
                    .as("A -free suffix is never evidence").isFalse();
        }
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, null)).isFalse();
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, "")).isFalse();
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, "   ")).isFalse();
        // A padded but genuinely admitted id resolves by its canonical form.
        assertThat(admission.admits(OpenCodeFreeOnlyAdmission.OPENCODE_ZEN_ADAPTER, "  big-pickle  ")).isTrue();
    }

    /** Other providers keep their existing behaviour: the policy is OpenCode-scoped only. */
    @Test
    void otherProvidersAreOutOfScopeAndUnaffected() {
        for (var adapter : List.of("MIMO", "CUSTOM_OPENAI_COMPATIBLE", "OPENAI")) {
            assertThat(admission.admits(adapter, "deepseek-v4-pro")).isTrue();
            assertThat(admission.admits(adapter, "any-model-name")).isTrue();
        }
        assertThat(admission.admits(null, "deepseek-v4-pro")).isTrue();
    }

    @Test
    void policyVersionAndAdmittedSetArePinned() {
        assertThat(OpenCodeFreeOnlyAdmission.POLICY_VERSION).isEqualTo("2026-10-16-v1");
        assertThat(OpenCodeFreeOnlyAdmission.ADMITTED_FREE_CHAT_MODELS)
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "big-pickle",
                        "longcat-2.5-preview-free",
                        "mimo-v2.6-flash-free",
                        "nemotron-3.5-lightning-free",
                        "space-bunny-free"));
    }

    /**
     * The Gateway barrier and the Backend barrier must execute the same rule. Both derive the free
     * set and version from the shared component, so the two deployables can never define conflicting
     * free rules.
     */
    @Test
    void gatewayBarrierExecutesTheSharedAdmissionPolicy() {
        assertThat(OpenCodeFreeOnlyAdmission.POLICY_VERSION)
                .isEqualTo(com.sharedlibs.opencodezen.OpenCodeZenFreeAdmissionPolicy.POLICY_VERSION);
        assertThat(OpenCodeFreeOnlyAdmission.ADMITTED_FREE_CHAT_MODELS)
                .isSameAs(com.sharedlibs.opencodezen.OpenCodeZenFreeAdmissionPolicy.ADMITTED_FREE_CHAT_MODELS);
    }
}
