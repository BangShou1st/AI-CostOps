package com.aicostops.attribution.application;

import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationSubjectType;

/** Draft of a new decision; the repository hard-codes status DRAFT. */
public record NewAllocationDecisionDraft(
        long organizationId,
        AllocationSubjectType subjectType,
        Long chargeFactId,
        Long expenseClaimId,
        AllocationDecisionSource decisionSource,
        Long allocationRuleId,
        Long createdByMemberId) {
}
