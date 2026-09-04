package com.aicostops.routing.domain;

public record RoutingPolicyCandidate(
        long id,
        long providerAccountId,
        long providerModelId,
        int priority,
        String status,
        String privacyRegionCode) {
}
