package com.aicostops.reconciliation.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.reconciliation.application.ReconciliationAuditPort;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class AuditReconciliationAdapter implements ReconciliationAuditPort {

    private final AuditService audit;

    public AuditReconciliationAdapter(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void runCompleted(long organizationId, long actorUserId, long runId,
            long billingPeriodId, long caseCount, String algorithmVersion) {
        audit.append("RECONCILIATION_RUN_COMPLETED", organizationId, actorUserId,
                "RECONCILIATION_RUN", runId,
                Map.of("billingPeriodId", billingPeriodId,
                        "caseCount", caseCount,
                        "algorithmVersion", algorithmVersion));
    }

    @Override
    public void runFailed(long organizationId, long actorUserId, long runId,
            long billingPeriodId, String errorCode) {
        audit.append("RECONCILIATION_RUN_FAILED", organizationId, actorUserId,
                "RECONCILIATION_RUN", runId,
                Map.of("billingPeriodId", billingPeriodId, "errorCode", errorCode));
    }

    @Override
    public void caseTransition(long organizationId, long actorUserId, long caseId,
            String action, String reasonCode) {
        var metadata = reasonCode == null
                ? Map.<String, Object>of("action", action)
                : Map.<String, Object>of("action", action, "reasonCode", reasonCode);
        audit.append("RECONCILIATION_CASE_" + action, organizationId, actorUserId,
                "RECONCILIATION_CASE", caseId, metadata);
    }

    @Override
    public void closeStarted(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, long closeGeneration, int attemptNo) {
        audit.append("PERIOD_CLOSE_STARTED", organizationId, actorUserId,
                "PERIOD_CLOSE_RUN", closeRunId,
                Map.of("billingPeriodId", billingPeriodId,
                        "closeGeneration", closeGeneration,
                        "attemptNo", attemptNo));
    }

    @Override
    public void closeBlocked(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, long failedCheckCount) {
        audit.append("PERIOD_CLOSE_BLOCKED", organizationId, actorUserId,
                "PERIOD_CLOSE_RUN", closeRunId,
                Map.of("billingPeriodId", billingPeriodId,
                        "failedCheckCount", failedCheckCount));
    }

    @Override
    public void closeFailed(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, String errorCode) {
        audit.append("PERIOD_CLOSE_FAILED", organizationId, actorUserId,
                "PERIOD_CLOSE_RUN", closeRunId,
                Map.of("billingPeriodId", billingPeriodId, "errorCode", errorCode));
    }

    @Override
    public void periodClosed(long organizationId, long actorUserId, long closeRunId,
            long billingPeriodId, long closeGeneration) {
        audit.append("PERIOD_CLOSED", organizationId, actorUserId,
                "BILLING_PERIOD", billingPeriodId,
                Map.of("closeRunId", closeRunId,
                        "closeGeneration", closeGeneration));
    }

    @Override
    public void periodReopened(long organizationId, long actorUserId, long billingPeriodId,
            long oldGeneration, long newGeneration, String reasonCode, String reasonNote) {
        audit.append("PERIOD_REOPENED", organizationId, actorUserId,
                "BILLING_PERIOD", billingPeriodId,
                Map.of("oldGeneration", oldGeneration,
                        "newGeneration", newGeneration,
                        "reasonCode", reasonCode,
                        "reasonNote", reasonNote));
    }
}
