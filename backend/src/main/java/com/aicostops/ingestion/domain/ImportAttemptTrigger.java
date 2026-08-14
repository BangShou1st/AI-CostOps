package com.aicostops.ingestion.domain;

public enum ImportAttemptTrigger {
    INITIAL,
    LEASE_RECOVERY,
    MANUAL_RETRY
}
