package com.aicostops.gateway.routing;

import com.aicostops.gateway.provider.ProviderChatAdapterRegistry;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CandidateEligibilityEvaluator {

    private final ProviderChatAdapterRegistry adapterRegistry;

    /** Lightweight constructor retained for pure evaluator tests. */
    public CandidateEligibilityEvaluator() {
        this.adapterRegistry = null;
    }

    @Autowired
    public CandidateEligibilityEvaluator(ProviderChatAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    public CandidateEligibility evaluate(ResolvedRoutingPolicy.Candidate candidate,
            RequestCapabilities capabilities, Set<ResolvedRoutingPolicy.RouteIdentity> attempted,
            Instant now) {
        if (attempted.contains(candidate.identity())) return CandidateEligibility.rejected(EligibilityReason.ALREADY_ATTEMPTED);
        if (candidate.providerAccountId() < 0) return CandidateEligibility.rejected(EligibilityReason.ACCOUNT_INACTIVE);
        if (!candidate.credentialReady()) return CandidateEligibility.rejected(EligibilityReason.CREDENTIAL_MISSING);
        if (candidate.providerModelId() < 0) return CandidateEligibility.rejected(EligibilityReason.MODEL_INACTIVE);
        if (!candidate.routingEligible()) return CandidateEligibility.rejected(EligibilityReason.MODEL_NOT_ROUTING_ELIGIBLE);
        if (candidate.providerCode() == null || candidate.providerModelName() == null) {
            return CandidateEligibility.rejected(EligibilityReason.LOGICAL_MODEL_MISMATCH);
        }
        if (candidate.adapterCode() == null || candidate.adapterCode().isBlank()) {
            return CandidateEligibility.rejected(EligibilityReason.ADAPTER_UNAVAILABLE);
        }
        if (adapterRegistry != null && !adapterRegistry.contains(candidate.adapterCode())) {
            return CandidateEligibility.rejected(EligibilityReason.ADAPTER_UNAVAILABLE);
        }
        if (!capabilities.chatCompletions() || !candidate.chatCapable()) {
            return CandidateEligibility.rejected(EligibilityReason.CHAT_CAPABILITY_MISMATCH);
        }
        if (capabilities.streaming() && !candidate.streamCapable()) {
            return CandidateEligibility.rejected(EligibilityReason.STREAM_CAPABILITY_MISMATCH);
        }
        if (candidate.pricingVersionId() == null || candidate.currency() == null || candidate.currency().isBlank()) {
            return CandidateEligibility.rejected(EligibilityReason.PRICING_UNAVAILABLE);
        }
        return CandidateEligibility.accepted();
    }

    public record RequestCapabilities(boolean chatCompletions, boolean streaming) {
    }

    public record CandidateEligibility(boolean eligible, EligibilityReason reason) {
        static CandidateEligibility accepted() { return new CandidateEligibility(true, null); }
        static CandidateEligibility rejected(EligibilityReason reason) { return new CandidateEligibility(false, reason); }
    }

    public enum EligibilityReason {
        ACCOUNT_INACTIVE,
        CREDENTIAL_MISSING,
        MODEL_INACTIVE,
        MODEL_NOT_ROUTING_ELIGIBLE,
        LOGICAL_MODEL_MISMATCH,
        ADAPTER_UNAVAILABLE,
        CHAT_CAPABILITY_MISMATCH,
        STREAM_CAPABILITY_MISMATCH,
        PRICING_UNAVAILABLE,
        ALREADY_ATTEMPTED
    }
}
