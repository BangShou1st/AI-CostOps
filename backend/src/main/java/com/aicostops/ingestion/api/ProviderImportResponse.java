package com.aicostops.ingestion.api;

/**
 * Browser-facing Provider Import creation response: identifiers are decimal
 * strings (BIGINT values may exceed JavaScript's safe-integer range).
 */
public record ProviderImportResponse(
        String evidenceId,
        String importBatchId,
        String latestAttemptId,
        String batchStatus,
        boolean duplicateEvidence,
        boolean duplicateBatch) {

    public static ProviderImportResponse of(
            long evidenceId,
            long importBatchId,
            long latestAttemptId,
            String batchStatus,
            boolean duplicateEvidence,
            boolean duplicateBatch) {
        return new ProviderImportResponse(
                Long.toString(evidenceId),
                Long.toString(importBatchId),
                Long.toString(latestAttemptId),
                batchStatus,
                duplicateEvidence,
                duplicateBatch);
    }
}
