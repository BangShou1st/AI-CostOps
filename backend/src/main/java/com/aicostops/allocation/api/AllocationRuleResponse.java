package com.aicostops.allocation.api;

import com.aicostops.attribution.domain.AllocationRule;
import com.aicostops.attribution.domain.AllocationRuleMatchType;
import com.aicostops.attribution.domain.AllocationRuleStatus;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;

/** HTTP shape of one immutable allocation rule version; ids are strings. */
public record AllocationRuleResponse(
        ApiId id,
        String ruleKey,
        int version,
        String name,
        String providerCode,
        ApiId providerAccountId,
        AllocationRuleMatchType matchHintType,
        String matchValue,
        int priority,
        ApiId targetProjectId,
        ApiId targetCostCenterId,
        ApiId targetTeamId,
        Instant effectiveFrom,
        Instant effectiveTo,
        AllocationRuleStatus status,
        ApiId createdByMemberId,
        Instant createdAt) {

    public static AllocationRuleResponse from(AllocationRule rule) {
        return new AllocationRuleResponse(
                ApiId.of(rule.id()),
                rule.ruleKey(),
                rule.version(),
                rule.name(),
                rule.providerCode(),
                rule.providerAccountId() == null ? null : ApiId.of(rule.providerAccountId()),
                rule.matchHintType(),
                rule.matchValue(),
                rule.priority(),
                rule.targetProjectId() == null ? null : ApiId.of(rule.targetProjectId()),
                rule.targetCostCenterId() == null ? null : ApiId.of(rule.targetCostCenterId()),
                rule.targetTeamId() == null ? null : ApiId.of(rule.targetTeamId()),
                rule.effectiveFrom(),
                rule.effectiveTo(),
                rule.status(),
                ApiId.of(rule.createdByMemberId()),
                rule.createdAt());
    }
}
