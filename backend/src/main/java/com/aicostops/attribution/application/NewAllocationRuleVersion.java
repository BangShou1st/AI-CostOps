package com.aicostops.attribution.application;

import com.aicostops.attribution.domain.AllocationRuleMatchType;
import java.time.Instant;

/** Draft of a new immutable rule version; the repository hard-codes ACTIVE. */
public record NewAllocationRuleVersion(
        long organizationId,
        String ruleKey,
        int version,
        String name,
        String providerCode,
        Long providerAccountId,
        AllocationRuleMatchType matchHintType,
        String matchValue,
        int priority,
        Long targetProjectId,
        Long targetCostCenterId,
        Long targetTeamId,
        Instant effectiveFrom,
        Instant effectiveTo,
        long createdByMemberId) {
}
