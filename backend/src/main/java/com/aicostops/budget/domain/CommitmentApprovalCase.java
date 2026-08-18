package com.aicostops.budget.domain;

import java.time.Instant;

/**
 * Budget-commitment view of the shared {@code approval_case} row (V12
 * subject BUDGET_COMMITMENT). One PENDING case is created with the request;
 * activation/reject/cancel transition it exactly once.
 */
public record CommitmentApprovalCase(
        long id,
        long organizationId,
        long budgetCommitmentId,
        CommitmentApprovalCaseStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
