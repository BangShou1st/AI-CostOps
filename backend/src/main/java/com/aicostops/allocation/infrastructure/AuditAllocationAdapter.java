package com.aicostops.allocation.infrastructure;

import com.aicostops.allocation.application.AllocationAuditPort;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.audit.application.AuditService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Allocation workflow audit adapter delegating to the shared AuditService. */
@Component
public class AuditAllocationAdapter implements AllocationAuditPort {

    private final AuditService auditService;

    public AuditAllocationAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void decisionConfirmed(long organizationId, long actorUserId, long decisionId,
            long chargeFactId, AllocationDecisionSource decisionSource, Long allocationRuleId,
            int lineCount, String currency) {
        var metadata = new HashMap<String, Object>();
        metadata.put("allocationDecisionId", decisionId);
        metadata.put("decisionSource", decisionSource.name());
        if (allocationRuleId != null) {
            metadata.put("allocationRuleId", allocationRuleId);
        }
        metadata.put("lineCount", lineCount);
        metadata.put("currency", currency);
        auditService.append("ALLOCATION_DECISION_CONFIRMED", organizationId, actorUserId,
                "CHARGE_FACT", chargeFactId, metadata);
    }
}
