package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.iam.domain.ScopeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Budget/period/commitment seam consumed by Ledger orchestration. */
public interface LedgerBudgetPort {

    BillingPeriod lockOpenPeriodAt(long organizationId, Instant effectiveAt);

    List<BudgetSelection> resolveSelections(
            long organizationId, long billingPeriodId, List<EntryScopeAmount> entries);

    List<Budget> lockBudgets(long organizationId, Collection<Long> budgetIds);

    List<BudgetCommitment> lockCommitments(long organizationId, Collection<Long> commitmentIds);

    void incrementActual(long organizationId, long budgetId, BigDecimal signedAmount, Instant now);

    record EntryScopeAmount(int entryIndex, ScopeType scopeType, long scopeId, String currency) {
    }

    record BudgetSelection(int entryIndex, Budget budget) {
    }
}
