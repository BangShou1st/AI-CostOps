package com.aicostops.gateway.routing;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DeterministicRouteSelector {

    public List<ResolvedRoutingPolicy.Candidate> orderedCandidates(ResolvedRoutingPolicy policy) {
        return policy.candidates().stream()
                .sorted(Comparator.comparingInt(ResolvedRoutingPolicy.Candidate::priority)
                        .thenComparingLong(ResolvedRoutingPolicy.Candidate::id))
                .toList();
    }
}
