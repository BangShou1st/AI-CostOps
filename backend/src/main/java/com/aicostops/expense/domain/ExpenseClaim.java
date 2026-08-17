package com.aicostops.expense.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Immutable expense claim state. {@code version} is the optimistic-lock
 * counter used by every owner mutation (edit, evidence attach, submit,
 * cancel); {@code currentAllocationDecisionId} is the materialized pointer to
 * the confirmed allocation decision (only written by the confirm transaction).
 */
public record ExpenseClaim(
        long id,
        long organizationId,
        long claimantMemberId,
        Long evidenceId,
        LocalDate expenseDate,
        BigDecimal amount,
        String currency,
        ExpenseClaimStatus status,
        Long currentAllocationDecisionId,
        Long approvalCaseId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isOwnedBy(long memberId) {
        return claimantMemberId == memberId;
    }

    /** postingReady is derived, never stored: APPROVED + a confirmed decision. */
    public boolean postingReady(boolean decisionConfirmed) {
        return status == ExpenseClaimStatus.APPROVED
                && currentAllocationDecisionId != null
                && decisionConfirmed;
    }
}
