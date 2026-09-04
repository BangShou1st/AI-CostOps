package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateEligibilityEvaluatorTest {

    private final CandidateEligibilityEvaluator evaluator = new CandidateEligibilityEvaluator();

    @Test
    void rejectsPreviouslyAttemptedCandidateAndMissingPricing() {
        var candidate = candidate(11, 12L, 13L, null, null);
        var attempted = evaluator.evaluate(candidate, new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of(candidate.identity()), Instant.EPOCH);
        var noPricing = evaluator.evaluate(candidate, new CandidateEligibilityEvaluator.RequestCapabilities(true, false),
                Set.of(), Instant.EPOCH);
        assertThat(attempted.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.ALREADY_ATTEMPTED);
        assertThat(noPricing.reason()).isEqualTo(CandidateEligibilityEvaluator.EligibilityReason.PRICING_UNAVAILABLE);
    }

    private static ResolvedRoutingPolicy.Candidate candidate(long id, Long accountId, Long modelId,
            Long pricingId, String currency) {
        return new ResolvedRoutingPolicy.Candidate(id, 0, accountId, "MIMO", modelId, "model", pricingId,
                currency, "https://example.test", "MIMO", true, true, true, true);
    }
}
