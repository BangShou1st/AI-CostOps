package com.aicostops.gateway.routing;

import com.aicostops.gateway.provider.ProviderChatAdapterRegistry;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CandidateEligibilityEvaluator {

    private final ProviderChatAdapterRegistry adapterRegistry;
    private final OpenCodeFreeOnlyAdmission openCodeFreeAdmission;
    private final KiloGatewayFreeOnlyAdmission kiloFreeAdmission;

    /** Lightweight constructor retained for pure evaluator tests. */
    public CandidateEligibilityEvaluator() {
        this.adapterRegistry = null;
        this.openCodeFreeAdmission = OpenCodeFreeOnlyAdmission.production();
        this.kiloFreeAdmission = new KiloGatewayFreeOnlyAdmission();
    }

    @Autowired
    public CandidateEligibilityEvaluator(ProviderChatAdapterRegistry adapterRegistry) {
        this(adapterRegistry, OpenCodeFreeOnlyAdmission.production(),
                new KiloGatewayFreeOnlyAdmission());
    }

    public CandidateEligibilityEvaluator(ProviderChatAdapterRegistry adapterRegistry,
            OpenCodeFreeOnlyAdmission openCodeFreeAdmission) {
        this(adapterRegistry, openCodeFreeAdmission, new KiloGatewayFreeOnlyAdmission());
    }

    public CandidateEligibilityEvaluator(ProviderChatAdapterRegistry adapterRegistry,
            OpenCodeFreeOnlyAdmission openCodeFreeAdmission,
            KiloGatewayFreeOnlyAdmission kiloFreeAdmission) {
        this.adapterRegistry = adapterRegistry;
        this.openCodeFreeAdmission = openCodeFreeAdmission;
        this.kiloFreeAdmission = kiloFreeAdmission;
    }

    public CandidateEligibility evaluateWithoutRuntimeModel(ResolvedRoutingPolicy.Candidate candidate,
            RequestCapabilities capabilities, Set<ResolvedRoutingPolicy.RouteIdentity> attempted) {
        return evaluateCandidate(candidate, null, capabilities, attempted);
    }

    /**
     * Runtime-bound eligibility check. The logical model is deliberately passed
     * from the frozen policy/request context so a malformed Provider Model row
     * cannot dispatch merely because Control Plane activation once accepted it.
     */
    public CandidateEligibility evaluate(ResolvedRoutingPolicy.Candidate candidate,
            long logicalModelId, RequestCapabilities capabilities,
            Set<ResolvedRoutingPolicy.RouteIdentity> attempted) {
        return evaluateCandidate(candidate, Long.valueOf(logicalModelId), capabilities, attempted);
    }

    private CandidateEligibility evaluateCandidate(ResolvedRoutingPolicy.Candidate candidate,
            Long logicalModelId, RequestCapabilities capabilities,
            Set<ResolvedRoutingPolicy.RouteIdentity> attempted) {
        if (attempted.contains(candidate.identity())) return CandidateEligibility.rejected(EligibilityReason.ALREADY_ATTEMPTED);
        if (candidate.providerAccountId() < 0) return CandidateEligibility.rejected(EligibilityReason.ACCOUNT_INACTIVE);
        // An admitted free built-in model (OpenCode Zen public credential, or Kilo Gateway anonymous
        // free call) is dispatched without a stored Provider key, so the DB-derived credential_ready
        // flag must not block it. This aligns readiness with the actual request credential selection
        // (ProviderExecutionContextResolver pins the keyless credential for the same model). Every
        // other Provider, and every non-admitted model, keeps the existing rule.
        boolean keylessFreeBuiltIn = isKeylessFreeBuiltIn(candidate.adapterCode(),
                candidate.providerModelName());
        if (!candidate.credentialReady() && !keylessFreeBuiltIn) {
            return CandidateEligibility.rejected(EligibilityReason.CREDENTIAL_MISSING);
        }
        if (candidate.providerModelId() < 0) return CandidateEligibility.rejected(EligibilityReason.MODEL_INACTIVE);
        if (!candidate.routingEligible()) return CandidateEligibility.rejected(EligibilityReason.MODEL_NOT_ROUTING_ELIGIBLE);
        if (candidate.providerCode() == null || candidate.providerModelName() == null
                || (logicalModelId != null
                    && candidate.providerModelLogicalModelId() != logicalModelId)
                || (logicalModelId != null
                    && !Objects.equals(candidate.providerCode(), candidate.providerModelProviderCode()))
                || !candidate.providerModelPairValid()) {
            return CandidateEligibility.rejected(EligibilityReason.LOGICAL_MODEL_MISMATCH);
        }
        if (candidate.providerConnectionProfileId() < 0 || candidate.baseUrl() == null
                || candidate.baseUrl().isBlank()) {
            return CandidateEligibility.rejected(EligibilityReason.CONNECTION_PROFILE_MISSING);
        }
        if (candidate.adapterCode() == null || candidate.adapterCode().isBlank()) {
            return CandidateEligibility.rejected(EligibilityReason.ADAPTER_UNAVAILABLE);
        }
        if (adapterRegistry != null && !adapterRegistry.contains(candidate.adapterCode())) {
            return CandidateEligibility.rejected(EligibilityReason.ADAPTER_UNAVAILABLE);
        }
        // Independent built-in FREE_ONLY barriers (OpenCode Zen 2026-10-16; Kilo Gateway 2026-10-18):
        // a model promoted before this round, carrying an ACTIVE Pricing Version and sitting on an
        // already-activated routing policy must still never reach Provider I/O. Evaluated from
        // server-owned Gateway state, never from Control Plane facts or a model-name suffix.
        if (!openCodeFreeAdmission.admits(candidate.adapterCode(), candidate.providerModelName())
                || !kiloFreeAdmission.admits(candidate.adapterCode(), candidate.providerModelName())) {
            return CandidateEligibility.rejected(EligibilityReason.FREE_ADMISSION_REQUIRED);
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

    /**
     * True when the frozen candidate may be dispatched without a stored Provider key: an admitted
     * free built-in model (OpenCode Zen public credential, or Kilo Gateway anonymous free call).
     * Non-built-in adapters and non-admitted models keep the credential_ready requirement.
     */
    private boolean isKeylessFreeBuiltIn(String adapterCode, String providerModelName) {
        return (openCodeFreeAdmission.isOpenCodeZen(adapterCode)
                && openCodeFreeAdmission.admits(adapterCode, providerModelName))
                || (kiloFreeAdmission.isKiloGateway(adapterCode)
                && kiloFreeAdmission.admits(adapterCode, providerModelName));
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
        FREE_ADMISSION_REQUIRED,
        ALREADY_ATTEMPTED,
        CONNECTION_PROFILE_MISSING
    }
}
