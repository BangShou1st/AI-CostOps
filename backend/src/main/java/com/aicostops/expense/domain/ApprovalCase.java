package com.aicostops.expense.domain;

import java.time.Instant;

/**
 * The single approval case of one expense. {@code status} mirrors the review
 * outcome of the expense and can only move through the frozen transitions of
 * {@link ApprovalCaseStatus}.
 */
public record ApprovalCase(
        long id,
        long organizationId,
        long expenseClaimId,
        ApprovalCaseStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
