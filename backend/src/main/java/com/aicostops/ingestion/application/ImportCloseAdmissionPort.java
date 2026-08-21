package com.aicostops.ingestion.application;

import java.time.Instant;

/** Consumer-owned admission seam used by Import workflows to serialize with period Close. */
public interface ImportCloseAdmissionPort {
    void lockAndRequireNoClosingPeriod(long organizationId);

    void lockAndRequireOpenPeriod(long organizationId, Instant periodStart);

    /**
     * Locks the organization admission row, then every BillingPeriod touched by
     * contributing canonical ChargeFacts of the attempt in ascending effective
     * period order. A period-less ChargeFact intentionally remains outside this
     * period-aware fence and follows the unknown-period admission contract.
     */
    void lockAndRequireOpenPeriodsForAttempt(long organizationId, long attemptId);
}
