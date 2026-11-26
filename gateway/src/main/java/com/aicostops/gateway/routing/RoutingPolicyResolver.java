package com.aicostops.gateway.routing;

import com.aicostops.gateway.cache.GatewayConfigCache;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoutingPolicyResolver {

    private final RoutingPolicyMapper mapper;
    private final GatewayConfigCache configCache;

    public RoutingPolicyResolver(RoutingPolicyMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public RoutingPolicyResolver(RoutingPolicyMapper mapper, GatewayConfigCache configCache) {
        this.mapper = mapper;
        this.configCache = configCache;
    }

    public ResolvedRoutingPolicy resolve(long orgId, long projectId, long logicalModelId, Instant now) {
        if (configCache != null) {
            var routeConfig = configCache.getRouteConfig(orgId, logicalModelId);
            var resolved = routeConfig == null ? null : routeConfig.resolvePolicy(projectId, now);
            if (resolved == null) {
                throw new IllegalStateException("No active routing policy is available");
            }
            return resolved;
        }
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
        if (configCache != null && candidate.providerModelLogicalModelId() > 0) {
            var routeConfig = configCache.getRouteConfig(orgId, candidate.providerModelLogicalModelId());
            if (routeConfig != null) {
                var pricing = routeConfig.findActivePricing(
                        candidate.providerAccountId(), candidate.providerModelId(), now);
                return pricing == null
                        ? candidate.withPricing(null, null)
                        : candidate.withPricing(pricing.pricingVersionId(), pricing.currency());
            }
        }
        var pricing = mapper.findCurrentPricing(orgId, candidate.providerAccountId(),
                candidate.providerModelId(), now);
        return pricing == null
                ? candidate.withPricing(null, null)
                : candidate.withPricing(pricing.pricingVersionId(), pricing.currency());
    }

    private ResolvedRoutingPolicy.Candidate candidate(RoutingPolicyMapper.CandidateRow row) {
        return new ResolvedRoutingPolicy.Candidate(
                row.id(), row.priority(), value(row.providerAccountId()), row.providerCode(), value(row.providerModelId()),
                row.providerModelName(), row.pricingVersionId(), row.currency(),
                row.baseUrl(), row.adapterCode(),
                row.credentialReady(), row.routingEligible(), row.chatCapable(), row.streamCapable(),
                value(row.providerModelLogicalModelId()), row.providerModelProviderCode(),
                value(row.providerConnectionProfileId()), row.completionPath(), row.protocolCode(),
                row.networkPolicy(), row.providerModelPairValid());
    }

    private static long value(Long value) {
        return value == null ? -1L : value;
    }
}
