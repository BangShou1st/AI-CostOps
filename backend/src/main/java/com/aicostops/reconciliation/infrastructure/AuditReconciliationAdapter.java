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
}
