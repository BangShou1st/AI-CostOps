package com.aicostops.ingestion.domain;

import java.time.Instant;

/**
 * One immutable execution of an {@link ImportBatch}. Retry/recovery never rewrites
 * a failed Attempt; each real execution gets a new row.
 */
public record ImportAttempt(
        long id,
        long importBatchId,
        int attemptNo,
        ImportAttemptStatus status,
        ImportAttemptTrigger triggerType,
        Long predecessorAttemptId,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        long leaseVersion,
        String parserVersion,
        String detectedProviderCode,
        String schemaFingerprint,
        Instant startedAt,
        Instant finishedAt,
        String errorCode,
        String errorSummary,
        long recordsSeen,
        long recordsValid,
        long warningCount,
        long errorCount,
        Instant createdAt) {
}
