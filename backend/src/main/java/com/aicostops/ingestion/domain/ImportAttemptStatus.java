package com.aicostops.ingestion.domain;

public enum ImportAttemptStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
