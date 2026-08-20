package com.aicostops.expense.application;

import com.aicostops.expense.domain.ExpenseClaimStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Posting-specific expense source contract owned by the expense module. */
public interface ExpensePostingPort {

    ExpensePostingSource load(long organizationId, long expenseId);

    ExpensePostingSource lockAndRequireApproved(
            long organizationId, long expenseId, long expectedDecisionId);

    void markPosted(long organizationId, long expenseId, long expectedVersion, Instant now);

    record ExpensePostingSource(
            long id,
            BigDecimal amount,
            String currency,
            LocalDate expenseDate,
            Long currentAllocationDecisionId,
            long version,
            ExpenseClaimStatus status) {
    }
}
