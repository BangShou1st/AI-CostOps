package com.aicostops.gateway.routing;

import java.util.List;
import java.util.Objects;

/** Immutable runtime projection of one ACTIVE policy and its ordered candidates. */
public record ResolvedRoutingPolicy(
        long id,
        int version,
        Long projectId,
        long logicalModelId,
        List<Candidate> candidates) {

    public ResolvedRoutingPolicy {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "Candidates are required"));
    }

    public record Candidate(
            long id,
            int priority,
            long providerAccountId,
            String providerCode,
            long providerModelId,
            String providerModelName,
            Long pricingVersionId,
            String currency,
            String baseUrl,
            String adapterCode,
            boolean credentialReady,
            boolean routingEligible,
            boolean chatCapable,
            boolean streamCapable) {

        public RouteIdentity identity() {
            return new RouteIdentity(providerAccountId, providerModelId);
        }

        public Candidate withPricing(Long newPricingVersionId, String newCurrency) {
            return new Candidate(id, priority, providerAccountId, providerCode, providerModelId,
                    providerModelName, newPricingVersionId, newCurrency, baseUrl, adapterCode,
                    credentialReady, routingEligible, chatCapable, streamCapable);
        }
    }

    public record RouteIdentity(long providerAccountId, long providerModelId) {
    }
}
