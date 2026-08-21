package com.aicostops.cost.application;

import java.time.Instant;
import java.util.List;

public interface AllocationCloseBlockerPort {
    long countUnallocatedCleanCharges(long organizationId, Instant periodStart, Instant periodEnd);
    List<Long> sampleUnallocatedChargeIds(long organizationId, Instant periodStart,
            Instant periodEnd, int limit);
}
