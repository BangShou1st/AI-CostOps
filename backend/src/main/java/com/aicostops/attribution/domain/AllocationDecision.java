package com.aicostops.attribution.domain;

import java.time.Instant;

/**
 * One allocation decision for exactly one subject. Exactly one of
 * {@code chargeFactId} / {@code expenseClaimId} is set; a RULE decision keeps
 * the {@code allocationRuleId} of the immutable rule version that produced it.
 * {@code createdByMemberId} is nullable because rule-generated decisions may
 * have no human creator.
 */
public record AllocationDecision(
        long id,
        long organizationId,
        AllocationSubjectType subjectType,
        Long chargeFactId,
        Long expenseClaimId,
        AllocationDecisionSource decisionSource,
        Long allocationRuleId,
        AllocationDecisionStatus status,
        Long createdByMemberId,
        Instant createdAt) {
}
