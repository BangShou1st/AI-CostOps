package com.aicostops.budget.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.budget.application.BudgetAuditPort;
import com.aicostops.iam.domain.ScopeType;
import java.math.BigDecimal;
import java.util.HashMap;
import org.springframework.stereotype.Component;

/** Budget management audit adapter delegating to the shared AuditService. */
@Component
public class AuditBudgetAdapter implements BudgetAuditPort {

    private final AuditService auditService;

    public AuditBudgetAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void created(long organizationId, long actorUserId, long budgetId,
            String currency, ScopeType scopeType, long scopeId, BigDecimal totalAmount) {
        var metadata = new HashMap<String, Object>();
        metadata.put("currency", currency);
        metadata.put("scopeType", scopeType.name());
        metadata.put("scopeId", scopeId);
        metadata.put("totalAmount", totalAmount.toPlainString());
        auditService.append("BUDGET_CREATED", organizationId, actorUserId,
                "BUDGET", budgetId, metadata);
    }

    @Override
    public void totalChanged(long organizationId, long actorUserId, long budgetId,
            long resultingVersion, BigDecimal totalAmount) {
        var metadata = new HashMap<String, Object>();
        metadata.put("version", resultingVersion);
        metadata.put("totalAmount", totalAmount.toPlainString());
        auditService.append("BUDGET_TOTAL_CHANGED", organizationId, actorUserId,
                "BUDGET", budgetId, metadata);
    }
}