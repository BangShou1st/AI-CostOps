package com.aicostops.budget.application;

import java.util.List;

public interface BudgetIntegrityPort {
    BudgetIntegritySnapshot inspect(long organizationId, long billingPeriodId);
    List<Long> sampleProblemBudgetIds(long organizationId, long billingPeriodId, int limit);

    record BudgetIntegritySnapshot(
            long actualAmountDrift,
            long committedAmountDrift,
            long invalidCommitmentState) {
        public long total() {
            return actualAmountDrift + committedAmountDrift + invalidCommitmentState;
        }
    }
}
