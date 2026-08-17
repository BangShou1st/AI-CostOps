package com.aicostops.expense.infrastructure;

import com.aicostops.expense.application.ExpenseAuditPort;
import com.aicostops.audit.application.AuditService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Expense workflow audit adapter delegating to the shared AuditService. */
@Component
public class AuditExpenseAdapter implements ExpenseAuditPort {

    private final AuditService auditService;

    public AuditExpenseAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void claimCreated(long organizationId, long actorUserId, long expenseId,
            String currency) {
        var metadata = new HashMap<String, Object>();
        metadata.put("currency", currency);
        auditService.append("EXPENSE_CREATED", organizationId, actorUserId,
                "EXPENSE_CLAIM", expenseId, metadata);
    }

    @Override
    public void claimEdited(long organizationId, long actorUserId, long expenseId,
            long resultingVersion, String currency) {
        var metadata = new HashMap<String, Object>();
        metadata.put("version", resultingVersion);
        metadata.put("currency", currency);
        auditService.append("EXPENSE_EDITED", organizationId, actorUserId,
                "EXPENSE_CLAIM", expenseId, metadata);
    }

    @Override
    public void submitted(long organizationId, long actorUserId, long expenseId,
            String actionType, long resultingVersion) {
        var metadata = new HashMap<String, Object>();
        metadata.put("actionType", actionType);
        metadata.put("version", resultingVersion);
        auditService.append("EXPENSE_SUBMITTED", organizationId, actorUserId,
                "EXPENSE_CLAIM", expenseId, metadata);
    }

    @Override
    public void canceled(long organizationId, long actorUserId, long expenseId,
            long resultingVersion) {
        var metadata = new HashMap<String, Object>();
        metadata.put("version", resultingVersion);
        auditService.append("EXPENSE_CANCELED", organizationId, actorUserId,
                "EXPENSE_CLAIM", expenseId, metadata);
    }

    @Override
    public void reviewed(long organizationId, long actorUserId, long expenseId,
            long resultingVersion, String actionType, String comment) {
        var metadata = new HashMap<String, Object>();
        metadata.put("actionType", actionType);
        metadata.put("version", resultingVersion);
        if (comment != null) {
            metadata.put("comment", comment);
        }
        auditService.append("EXPENSE_REVIEWED", organizationId, actorUserId,
                "EXPENSE_CLAIM", expenseId, metadata);
    }

    @Override
    public void evidenceAttached(long organizationId, long actorUserId, long expenseId,
            long evidenceId, long resultingVersion) {
        var metadata = new HashMap<String, Object>();
        metadata.put("evidenceId", evidenceId);
        metadata.put("version", resultingVersion);
        auditService.append("EXPENSE_EVIDENCE_ATTACHED", organizationId, actorUserId,
                "EXPENSE_CLAIM", expenseId, metadata);
    }
}
