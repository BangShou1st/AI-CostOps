package com.aicostops.reconciliation.application;

public interface ReconciliationAuditPort {
    void runCompleted(long organizationId, long actorUserId, long runId,
            long billingPeriodId, long caseCount, String algorithmVersion);
    void runFailed(long organizationId, long actorUserId, long runId,
            long billingPeriodId, String errorCode);
    void caseTransition(long organizationId, long actorUserId, long caseId,
            String action, String reasonCode);
    void adjustmentPosted(long organizationId, long actorUserId, long adjustmentId,
            long caseId, long runId, String scope, java.math.BigDecimal amount,
            String currency, long adjustmentPeriodId);
    void closeStarted(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, long closeGeneration, int attemptNo);
    void closeBlocked(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, long failedCheckCount);
    void closeFailed(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, String errorCode);
    void periodClosed(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, long closeGeneration);
    void periodReopened(long organizationId, long actorUserId, long billingPeriodId,
            long oldGeneration, long newGeneration, String reasonCode, String reasonNote);
}
