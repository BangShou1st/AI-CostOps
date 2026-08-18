package com.aicostops.budget.domain;

import com.aicostops.iam.domain.ScopeType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable budget state. The scope is polymorphic ({@code scopeType} /
 * {@code scopeId} reference a same-organization resource validated by the
 * application layer). {@code actualAmount} may be negative (credits /
 * reversals); {@code totalAmount} and {@code committedAmount} never are.
 *
 * <p>{@code available} is derived, never stored as a second authoritative
 * column: {@code total - actual - committed}. {@code overBudget} means
 * available is negative.
 */
public record Budget(
        long id,
        long organizationId,
        long billingPeriodId,
        ScopeType scopeType,
        long scopeId,
        String currency,
        BigDecimal totalAmount,
        BigDecimal actualAmount,
        BigDecimal committedAmount,
        BudgetStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** {@code available = total_amount - actual_amount - committed_amount}. */
    public BigDecimal available() {
        return totalAmount.subtract(actualAmount).subtract(committedAmount);
    }

    public boolean overBudget() {
        return available().signum() < 0;
    }
}