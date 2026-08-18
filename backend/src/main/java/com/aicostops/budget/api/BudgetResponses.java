package com.aicostops.budget.api;

import com.aicostops.budget.domain.Budget;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;

/**
 * Budget API responses. Money is a plain decimal string (8 fractional
 * digits); ids are strings; availableAmount and overBudget are computed
 * server-side from DB truth (available = total - actual - committed).
 */
public final class BudgetResponses {

    private BudgetResponses() {
    }

    public record BudgetResponse(
            ApiId id,
            ApiId billingPeriodId,
            String scopeType,
            ApiId scopeId,
            String currency,
            String totalAmount,
            String actualAmount,
            String committedAmount,
            String availableAmount,
            boolean overBudget,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static BudgetResponse from(Budget budget) {
            return new BudgetResponse(
                    ApiId.of(budget.id()),
                    ApiId.of(budget.billingPeriodId()),
                    budget.scopeType().name(),
                    ApiId.of(budget.scopeId()),
                    budget.currency(),
                    budget.totalAmount().toPlainString(),
                    budget.actualAmount().toPlainString(),
                    budget.committedAmount().toPlainString(),
                    budget.available().toPlainString(),
                    budget.overBudget(),
                    budget.status().name(),
                    budget.version(),
                    budget.createdAt(),
                    budget.updatedAt());
        }
    }
}