package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateEligibilityEvaluatorTest {

    private final CandidateEligibilityEvaluator evaluator = new CandidateEligibilityEvaluator();

    @Test
    void rejectsPreviouslyAttemptedCandidateAndMissingPricing() {
        var candidate = candidate(11, 12L, 13L, null, null);
        var attempted = evaluator.evaluate(candidate, new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of(candidate.identity()));
        var noPricing = evaluator.evaluate(candidate, new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of());
        assertThat(attempted.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.ALREADY_ATTEMPTED);
        assertThat(noPricing.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.PRICING_UNAVAILABLE);
    }

    @Test
    void rejectsProviderModelThatDoesNotMatchFrozenLogicalModel() {
        var candidate = candidate(11, 12L, 13L, 7L, "USD", 99L, "MIMO");

        var result = evaluator.evaluate(candidate, 7L,
                new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of());

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.LOGICAL_MODEL_MISMATCH);
    }

    @Test
    void rejectsProviderAccountAndProviderModelCodeMismatch() {
        var candidate = candidate(11, 12L, 13L, 7L, "USD", 7L, "OPENAI");

        var result = evaluator.evaluate(candidate, 7L,
                new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of());

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.LOGICAL_MODEL_MISMATCH);
    }

    private static ResolvedRoutingPolicy.Candidate candidate(long id, Long accountId, Long modelId,
            Long pricingId, String currency) {
        return new ResolvedRoutingPolicy.Candidate(id, 0, accountId, "MIMO", modelId, "model", pricingId,
                currency, "https://example.test", "MIMO", true, true, true, true);
    }

    private static ResolvedRoutingPolicy.Candidate candidate(long id, Long accountId, Long modelId,
            Long pricingId, String currency, long logicalModelId, String providerModelCode) {
        return new ResolvedRoutingPolicy.Candidate(id, 0, accountId, "MIMO", modelId, "model", pricingId,
                currency, "https://example.test", "MIMO", true, true, true, true,
                logicalModelId, providerModelCode);
    }
}
