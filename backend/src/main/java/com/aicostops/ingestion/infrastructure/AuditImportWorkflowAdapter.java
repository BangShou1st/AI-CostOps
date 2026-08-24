package com.aicostops.ingestion.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.ingestion.application.ImportWorkflowAuditPort;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Delegates Import workflow audit events to the low-level {@link AuditService}.
 * Metadata carries only identifiers and status names; provider payloads,
 * filenames, credentials, issue messages, and object-store keys are never part
 * of an audit event.
 */
@Component
public class AuditImportWorkflowAdapter implements ImportWorkflowAuditPort {

    private static final String SUBJECT_TYPE_IMPORT_BATCH = "IMPORT_BATCH";

    private final AuditService auditService;

    public AuditImportWorkflowAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void importRetried(long orgId, long actorUserId, long batchId,
            long predecessorAttemptId, long newAttemptId, String previousBatchStatus) {
        auditService.append("IMPORT_RETRIED", orgId, actorUserId, SUBJECT_TYPE_IMPORT_BATCH, batchId,
                Map.of(
                        "predecessorAttemptId", predecessorAttemptId,
                        "newAttemptId", newAttemptId,
                        "previousBatchStatus", previousBatchStatus));
    }

    @Override
    public void importCanceled(long orgId, long actorUserId, long batchId,
            long attemptId, String previousAttemptStatus, String previousBatchStatus) {
        auditService.append("IMPORT_CANCELED", orgId, actorUserId, SUBJECT_TYPE_IMPORT_BATCH, batchId,
                Map.of(
                        "attemptId", attemptId,
                        "latestAttemptId", attemptId,
                        "previousAttemptStatus", previousAttemptStatus,
                        "latestAttemptStatus", previousAttemptStatus,
                        "previousBatchStatus", previousBatchStatus,
                        "terminalBatchStatus", "CANCELED"));
    }

    @Override
    public void importConfirmed(long orgId, long actorUserId, long batchId,
            long attemptId, String previousBatchStatus) {
        auditService.append("IMPORT_CONFIRMED", orgId, actorUserId, SUBJECT_TYPE_IMPORT_BATCH, batchId,
                Map.of(
                        "attemptId", attemptId,
                        "previousBatchStatus", previousBatchStatus));
    }
}
