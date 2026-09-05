package com.aicostops.routing.api;

import com.aicostops.routing.domain.RoutingPolicy;
import com.aicostops.routing.domain.RoutingPolicyCandidate;
import com.aicostops.routing.domain.RoutingPolicyStatus;
import com.aicostops.shared.json.ApiId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class RoutingPolicyDtos {

    private RoutingPolicyDtos() {
    }

    public record CandidateInput(
            @Min(1) long providerAccountId,
            @Min(1) long providerModelId,
            @Min(0) int priority,
            String status,
            String privacyRegionCode) {
    }

    public record CreateRequest(
            Long projectId,
            @Min(1) long modelId,
            @NotEmpty List<@Valid CandidateInput> candidates) {
    }

    public record UpdateRequest(
            @NotEmpty List<@Valid CandidateInput> candidates) {
    }

    public record CandidateResponse(
            ApiId id,
            ApiId providerAccountId,
            ApiId providerModelId,
            int priority,
            String status,
            String privacyRegionCode) {

        static CandidateResponse from(RoutingPolicyCandidate candidate) {
            return new CandidateResponse(
                    ApiId.of(candidate.id()),
                    ApiId.of(candidate.providerAccountId()),
                    ApiId.of(candidate.providerModelId()),
                    candidate.priority(), candidate.status(), candidate.privacyRegionCode());
        }
    }

    public record PolicyResponse(
            ApiId id,
            ApiId organizationId,
            ApiId projectId,
            ApiId modelId,
            int version,
            RoutingPolicyStatus status,
            List<CandidateResponse> candidates) {

        public static PolicyResponse from(RoutingPolicy policy) {
            return new PolicyResponse(
                    ApiId.of(policy.id()),
                    ApiId.of(policy.organizationId()),
                    policy.projectId() == null ? null : ApiId.of(policy.projectId()),
                    ApiId.of(policy.modelId()),
                    policy.version(), policy.status(),
                    policy.candidates().stream().map(CandidateResponse::from).toList());
        }
    }

    public record RouteOptionResponse(
            ApiId providerAccountId,
            String displayName,
            String providerCode,
            ApiId providerModelId,
            String providerModelName,
            boolean routingEligible,
            boolean credentialReady,
            boolean pricingReady,
            List<String> currencies) {
    }
}
