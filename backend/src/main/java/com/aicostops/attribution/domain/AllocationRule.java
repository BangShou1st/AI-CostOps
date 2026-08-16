package com.aicostops.attribution.domain;

import java.time.Instant;

/**
 * One immutable allocation rule version, unique per
 * {@code (organizationId, ruleKey, version)}. Definition columns are never
 * updated; lifecycle changes append a new version. The effective range is the
 * half-open interval {@code [effectiveFrom, effectiveTo)}.
 */
public record AllocationRule(
        long id,
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
        AllocationRuleStatus status,
        long createdByMemberId,
        Instant createdAt) {
}
