package com.aicostops.cost.review.application;

import java.time.Instant;
import java.util.List;

public interface DuplicateCloseBlockerPort {
    long countUnresolvedDuplicates(long organizationId, Instant periodStart, Instant periodEnd);
    List<Long> sampleUnresolvedDuplicateIds(long organizationId, Instant periodStart,
            Instant periodEnd, int limit);
}
