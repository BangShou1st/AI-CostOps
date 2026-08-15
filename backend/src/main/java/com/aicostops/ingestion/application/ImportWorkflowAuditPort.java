package com.aicostops.ingestion.application;

/**
 * Ingestion-owned audit boundary for Import workflow commands. Implementations
 * must record secret-free metadata (identifiers/status values only); insertion
 * failure must roll back the workflow mutation.
 */
public interface ImportWorkflowAuditPort {

    void importRetried(long orgId, long actorUserId, long batchId,
            long predecessorAttemptId, long newAttemptId, String previousBatchStatus);

    void importCanceled(long orgId, long actorUserId, long batchId,
            long attemptId, String previousAttemptStatus, String previousBatchStatus);
}
