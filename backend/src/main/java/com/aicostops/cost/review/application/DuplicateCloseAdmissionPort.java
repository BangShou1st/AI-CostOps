package com.aicostops.cost.review.application;

import java.time.Instant;

/** Consumer-owned admission seam used by duplicate truth changes to serialize with period Close. */
public interface DuplicateCloseAdmissionPort {
    void lockAndRequireNoClosingPeriod(long organizationId);
    void lockIfCoveredAndRequireOpenAt(long organizationId, Instant effectiveAt);
}
