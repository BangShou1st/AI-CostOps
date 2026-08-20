package com.aicostops.ingestion.application;

/** Consumer-owned admission seam used by Import workflows to serialize with period Close. */
public interface ImportCloseAdmissionPort {
    void lockAndRequireNoClosingPeriod(long organizationId);
}
