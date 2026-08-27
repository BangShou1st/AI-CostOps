package com.aicostops.allocation.infrastructure;

import com.aicostops.allocation.application.AllocationAuditPort;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationSubjectType;
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
            AllocationSubjectType subjectType, long subjectId,
            AllocationDecisionSource decisionSource, Long allocationRuleId,
            int lineCount, String currency) {
        var metadata = new HashMap<String, Object>();
        metadata.put("allocationDecisionId", decisionId);
        metadata.put("decisionSource", decisionSource.name());
        if (allocationRuleId != null) {
            metadata.put("allocationRuleId", allocationRuleId);
        }
        metadata.put("lineCount", lineCount);
        metadata.put("currency", currency);
        // Charge output is byte-compatible with the M3 contract: subjectType
        // CHARGE_FACT with the charge id as subject id.
        auditService.append("ALLOCATION_DECISION_CONFIRMED", organizationId, actorUserId,
                subjectType.name(), subjectId, metadata);
    }

    @Override
    public void ruleVersionPublished(long organizationId, long actorUserId,
            long allocationRuleId, String ruleKey, int version) {
        var metadata = new HashMap<String, Object>();
        metadata.put("ruleKey", ruleKey);
        metadata.put("version", version);
        auditService.append("ALLOCATION_RULE_VERSION_PUBLISHED", organizationId, actorUserId,
                "ALLOCATION_RULE", allocationRuleId, metadata);
    }

    @Override
    public void ruleArchived(long organizationId, long actorUserId,
            long allocationRuleId, String ruleKey, int version) {
        var metadata = new HashMap<String, Object>();
        metadata.put("ruleKey", ruleKey);
        metadata.put("version", version);
        auditService.append("ALLOCATION_RULE_ARCHIVED", organizationId, actorUserId,
                "ALLOCATION_RULE", allocationRuleId, metadata);
    }
}