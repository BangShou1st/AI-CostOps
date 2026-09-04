package com.aicostops.routing.domain;

import java.util.List;

public record RoutingPolicy(
        long id,
        long organizationId,
        Long projectId,
        long modelId,
        int version,
        RoutingPolicyStatus status,
        List<RoutingPolicyCandidate> candidates) {

    public RoutingPolicy {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }
}
