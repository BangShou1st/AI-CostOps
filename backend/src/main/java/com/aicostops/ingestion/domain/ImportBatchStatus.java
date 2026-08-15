package com.aicostops.ingestion.domain;

public enum ImportBatchStatus {
    PENDING,
    PROCESSING,
    PARSED,
    READY_FOR_REVIEW,
    CONFIRMED,
    FAILED,
    CANCELED
}
