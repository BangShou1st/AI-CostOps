package com.aicostops.ingestion.api;

public record ProviderImportResponse(
        long evidenceId,
        long importBatchId,
        long latestAttemptId,
        String batchStatus,
        boolean duplicateEvidence,
        boolean duplicateBatch) {
}
