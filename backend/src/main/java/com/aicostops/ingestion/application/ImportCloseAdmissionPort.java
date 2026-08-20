package com.aicostops.ingestion.application;

import java.time.Instant;

/** Consumer-owned admission seam used by Import workflows to serialize with period Close. */
public interface ImportCloseAdmissionPort {
    void lockAndRequireNoClosingPeriod(long organizationId);

    void lockAndRequireOpenPeriod(long organizationId, Instant periodStart);
}
