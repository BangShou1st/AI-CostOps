package com.aicostops.ingestion.domain;

public enum ImportBatchStatus {
    PENDING,
    PROCESSING,
    PARSED,
    FAILED,
    CANCELED
}
