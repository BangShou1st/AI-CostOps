package com.aicostops.budget.application;

import com.aicostops.iam.domain.ScopeType;
import java.math.BigDecimal;

/** Budget management commands. */
public final class BudgetCommands {

    private BudgetCommands() {
    }

    public record CreateBudgetCommand(
            long billingPeriodId,
            ScopeType scopeType,
            long scopeId,
            String currency,
            BigDecimal totalAmount) {
    }

    /**
     * Only manageable fields: {@code totalAmount} with the optimistic version
     * CAS. {@code actualAmount} / {@code committedAmount} are financial
     * counters owned exclusively by posting / commitment transactions and are
     * never writable through the management API.
     */
    public record UpdateBudgetCommand(
            BigDecimal totalAmount,
            long expectedVersion) {
    }
}