package com.aicostops.gateway.routing;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class RoutingPolicyResolver {

    private final RoutingPolicyMapper mapper;

    public RoutingPolicyResolver(RoutingPolicyMapper mapper) {
        this.mapper = mapper;
    }

    public ResolvedRoutingPolicy resolve(long orgId, long projectId, long logicalModelId, Instant now) {
        var exact = mapper.findActiveExact(orgId, projectId, logicalModelId);
        // A present exact project policy wins even if its candidate set is empty.
        // Falling back in that case would silently bypass an administrator's
        // explicit project-level deny/configuration.
        var selected = exact == null
                ? mapper.findActiveOrganizationDefault(orgId, logicalModelId)
                : exact;
        if (selected == null) {
            throw new IllegalStateException("No active routing policy is available");
        }
        return new ResolvedRoutingPolicy(selected.id(), selected.version(), selected.projectId(),
                selected.modelId(), mapper.findCandidates(orgId, selected.id(), now).stream()
                        .map(this::candidate)
                .toList());
    }

    /** Re-resolves only the mutable pricing projection for a frozen candidate. */
    public ResolvedRoutingPolicy.Candidate refreshPricing(long orgId,
            ResolvedRoutingPolicy.Candidate candidate, Instant now) {
        var pricing = mapper.findCurrentPricing(orgId, candidate.providerAccountId(),
                candidate.providerModelId(), now);
        return pricing == null
                ? candidate.withPricing(null, null)
                : candidate.withPricing(pricing.pricingVersionId(), pricing.currency());
    }

    private ResolvedRoutingPolicy.Candidate candidate(RoutingPolicyMapper.CandidateRow row) {
        return new ResolvedRoutingPolicy.Candidate(
                row.id(), row.priority(), value(row.providerAccountId()), row.providerCode(), value(row.providerModelId()),
                row.providerModelName(), row.pricingVersionId(), row.currency(), row.baseUrl(), row.adapterCode(),
                row.credentialReady(), row.routingEligible(), row.chatCapable(), row.streamCapable(),
                value(row.providerModelLogicalModelId()), row.providerModelProviderCode());
    }

    private static long value(Long value) {
        return value == null ? -1L : value;
    }
}
