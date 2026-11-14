package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateEligibilityEvaluatorTest {

    private final CandidateEligibilityEvaluator evaluator = new CandidateEligibilityEvaluator();

    @Test
    void rejectsPreviouslyAttemptedCandidateAndMissingPricing() {
        var candidate = candidate(11, 12L, 13L, null, null);
        var attempted = evaluator.evaluateWithoutRuntimeModel(candidate, new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of(candidate.identity()));
        var noPricing = evaluator.evaluateWithoutRuntimeModel(candidate, new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of());
        assertThat(attempted.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.ALREADY_ATTEMPTED);
        assertThat(noPricing.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.PRICING_UNAVAILABLE);
    }

    @Test
    void rejectsProviderModelThatDoesNotMatchFrozenLogicalModel() {
        var candidate = candidate(11, 12L, 13L, 7L, "USD", 99L, "OPENAI");

        var result = evaluator.evaluate(candidate, 7L,
                new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of());

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.LOGICAL_MODEL_MISMATCH);
    }

    @Test
    void rejectsProviderAccountAndProviderModelCodeMismatch() {
        var candidate = candidate(11, 12L, 13L, 7L, "USD", 7L, "OTHER");

        var result = evaluator.evaluate(candidate, 7L,
                new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of());

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.LOGICAL_MODEL_MISMATCH);
    }

    /** Other Providers keep the existing rule: no ACTIVE credential still means CREDENTIAL_MISSING. */
    @Test
    void otherProvidersWithoutACredentialStillFailClosed() {
        var openAi = adapters("OPENAI", "gpt-test", false);
        var custom = adapters("CUSTOM_OPENAI_COMPATIBLE", "any-model", false);

        assertThat(evaluator.evaluateWithoutRuntimeModel(openAi,
                new CandidateEligibilityEvaluator.RequestCapabilities(true, false), Set.of()).reason())
                .isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.CREDENTIAL_MISSING);
        assertThat(evaluator.evaluateWithoutRuntimeModel(custom,
                new CandidateEligibilityEvaluator.RequestCapabilities(true, false), Set.of()).reason())
                .isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.CREDENTIAL_MISSING);
    }

    private static ResolvedRoutingPolicy.Candidate adapters(String adapterCode,
            String providerModelName, boolean credentialReady) {
        return new ResolvedRoutingPolicy.Candidate(11L, 0, 12L, adapterCode, 13L, providerModelName,
                7L, "USD", "https://provider.example.test/v1", adapterCode, credentialReady, true, true, true,
                -1L, null, 77L, "/chat/completions", "OPENAI_CHAT_COMPLETIONS",
                "DIRECT_ONLY", true);
    }

    private static ResolvedRoutingPolicy.Candidate candidate(long id, Long accountId, Long modelId,
            Long pricingId, String currency) {
        return new ResolvedRoutingPolicy.Candidate(id, 0, accountId, "OPENAI", modelId, "model", pricingId,
                currency, "https://example.test", "OPENAI", true, true, true, true,
                -1L, null, 77L, "/chat/completions", "OPENAI_CHAT_COMPLETIONS",
                "DIRECT_PUBLIC_ONLY", true);
    }

    private static ResolvedRoutingPolicy.Candidate candidate(long id, Long accountId, Long modelId,
            Long pricingId, String currency, long logicalModelId, String providerModelCode) {
        return new ResolvedRoutingPolicy.Candidate(id, 0, accountId, "OPENAI", modelId, "model", pricingId,
                currency, "https://example.test", "OPENAI", true, true, true, true,
                logicalModelId, providerModelCode, 77L, "/chat/completions",
                "OPENAI_CHAT_COMPLETIONS", "DIRECT_PUBLIC_ONLY", true);
    }
}
