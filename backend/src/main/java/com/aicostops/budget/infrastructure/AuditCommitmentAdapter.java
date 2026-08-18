package com.aicostops.budget.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.budget.application.CommitmentAuditPort;
import java.math.BigDecimal;
import java.util.HashMap;
import org.springframework.stereotype.Component;

/** Commitment lifecycle audit adapter delegating to the shared AuditService. */
@Component
public class AuditCommitmentAdapter implements CommitmentAuditPort {

    private final AuditService auditService;

    public AuditCommitmentAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void requested(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal requestedAmount) {
        var metadata = new HashMap<String, Object>();
        metadata.put("budgetId", budgetId);
        metadata.put("requestedAmount", requestedAmount.toPlainString());
        auditService.append("COMMITMENT_REQUESTED", organizationId, actorUserId,
                "BUDGET_COMMITMENT", commitmentId, metadata);
    }

    @Override
    public void activated(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal approvedAmount, long approvalCaseId,
            String fromStatus, String toStatus) {
        var metadata = new HashMap<String, Object>();
        metadata.put("budgetId", budgetId);
        metadata.put("approvalCaseId", approvalCaseId);
        metadata.put("fromStatus", fromStatus);
        metadata.put("toStatus", toStatus);
        metadata.put("approvedAmount", approvedAmount.toPlainString());
        auditService.append("COMMITMENT_ACTIVATED", organizationId, actorUserId,
                "BUDGET_COMMITMENT", commitmentId, metadata);
    }

    @Override
    public void rejected(long organizationId, long actorUserId, long commitmentId,
            long budgetId, long approvalCaseId, String fromStatus, String toStatus) {
        var metadata = new HashMap<String, Object>();
        metadata.put("budgetId", budgetId);
        metadata.put("approvalCaseId", approvalCaseId);
        metadata.put("fromStatus", fromStatus);
        metadata.put("toStatus", toStatus);
        auditService.append("COMMITMENT_REJECTED", organizationId, actorUserId,
                "BUDGET_COMMITMENT", commitmentId, metadata);
    }

    @Override
    public void canceled(long organizationId, long actorUserId, long commitmentId,
            long budgetId, long approvalCaseId, String fromStatus, String toStatus) {
        var metadata = new HashMap<String, Object>();
        metadata.put("budgetId", budgetId);
        metadata.put("approvalCaseId", approvalCaseId);
        metadata.put("fromStatus", fromStatus);
        metadata.put("toStatus", toStatus);
        auditService.append("COMMITMENT_CANCELED", organizationId, actorUserId,
                "BUDGET_COMMITMENT", commitmentId, metadata);
    }

    @Override
    public void released(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal releasedAmount, long approvalCaseId,
            String fromStatus, String toStatus) {
        var metadata = new HashMap<String, Object>();
        metadata.put("budgetId", budgetId);
        metadata.put("approvalCaseId", approvalCaseId);
        metadata.put("fromStatus", fromStatus);
        metadata.put("toStatus", toStatus);
        metadata.put("releasedAmount", releasedAmount.toPlainString());
        auditService.append("COMMITMENT_RELEASED", organizationId, actorUserId,
                "BUDGET_COMMITMENT", commitmentId, metadata);
    }

    @Override
    public void consumed(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal consumedAmount, long ledgerEntryId,
            String fromStatus, String toStatus) {
        var metadata = new HashMap<String, Object>();
        metadata.put("budgetId", budgetId);
        metadata.put("ledgerEntryId", ledgerEntryId);
        metadata.put("fromStatus", fromStatus);
        metadata.put("toStatus", toStatus);
        metadata.put("consumedAmount", consumedAmount.toPlainString());
        auditService.append("COMMITMENT_CONSUMED", organizationId, actorUserId,
                "BUDGET_COMMITMENT", commitmentId, metadata);
    }
}
