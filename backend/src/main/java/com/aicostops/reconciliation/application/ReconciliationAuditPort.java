package com.aicostops.reconciliation.application;

public interface ReconciliationAuditPort {
    void runCompleted(long organizationId, long actorUserId, long runId,
            long billingPeriodId, long caseCount, String algorithmVersion);
    void runFailed(long organizationId, long actorUserId, long runId,
            long billingPeriodId, String errorCode);
    void caseTransition(long organizationId, long actorUserId, long caseId,
            String action, String reasonCode);
}
