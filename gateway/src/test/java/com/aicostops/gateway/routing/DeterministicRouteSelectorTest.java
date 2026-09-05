package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicRouteSelectorTest {

    @Test
    void ordersByPriorityThenCandidateId() {
        var policy = new ResolvedRoutingPolicy(1, 1, null, 7, List.of(
                candidate(30, 2), candidate(10, 0), candidate(20, 0), candidate(40, 1)));
        assertThat(new DeterministicRouteSelector().orderedCandidates(policy))
                .extracting(ResolvedRoutingPolicy.Candidate::id)
                .containsExactly(10L, 20L, 40L, 30L);
    }

    private static ResolvedRoutingPolicy.Candidate candidate(long id, int priority) {
        return new ResolvedRoutingPolicy.Candidate(id, priority, id, "MIMO", id, "model", id,
                "USD", "https://example.test", "MIMO", true, true, true, true);
    }
}
