package com.aicostops.cost.review.application;

/** Consumer-owned admission seam used by duplicate truth changes to serialize with period Close. */
public interface DuplicateCloseAdmissionPort {
    void lockAndRequireNoClosingPeriod(long organizationId);
}
