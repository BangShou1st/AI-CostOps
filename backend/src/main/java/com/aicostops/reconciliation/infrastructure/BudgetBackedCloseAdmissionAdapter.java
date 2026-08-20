package com.aicostops.reconciliation.infrastructure;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.cost.review.application.DuplicateCloseAdmissionPort;
import com.aicostops.expense.application.ExpenseCloseAdmissionPort;
import com.aicostops.ingestion.application.ImportCloseAdmissionPort;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** M6 composition adapter from consumer-owned admission ports to Budget period locking. */
@Component
public final class BudgetBackedCloseAdmissionAdapter implements
        ImportCloseAdmissionPort, DuplicateCloseAdmissionPort, ExpenseCloseAdmissionPort {

    private final BillingPeriodFinancialWriteFence fence;

    public BudgetBackedCloseAdmissionAdapter(BillingPeriodFinancialWriteFence fence) {
        this.fence = fence;
    }

    @Override
    public void lockAndRequireNoClosingPeriod(long organizationId) {
        fence.lockOrganizationAndRequireNoClosingPeriod(organizationId);
    }

    @Override
    public void lockOpenAt(long organizationId, Instant effectiveAt) {
        fence.lockOpenAt(organizationId, effectiveAt);
    }
}
