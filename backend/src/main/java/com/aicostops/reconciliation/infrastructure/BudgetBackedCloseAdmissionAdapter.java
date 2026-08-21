package com.aicostops.reconciliation.infrastructure;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.cost.review.application.DuplicateCloseAdmissionPort;
import com.aicostops.expense.application.ExpenseCloseAdmissionPort;
import com.aicostops.ingestion.application.ImportCloseAdmissionPort;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** M6 composition adapter from consumer-owned admission ports to Budget period locking. */
@Component
public final class BudgetBackedCloseAdmissionAdapter implements
        ImportCloseAdmissionPort, DuplicateCloseAdmissionPort, ExpenseCloseAdmissionPort {

    private final BillingPeriodFinancialWriteFence fence;
    private final ImportChargePeriodMapper importChargePeriods;

    public BudgetBackedCloseAdmissionAdapter(
            BillingPeriodFinancialWriteFence fence,
            ImportChargePeriodMapper importChargePeriods) {
        this.fence = fence;
        this.importChargePeriods = importChargePeriods;
    }

    @Override
    public void lockAndRequireNoClosingPeriod(long organizationId) {
        fence.lockOrganizationAndRequireNoClosingPeriod(organizationId);
    }

    @Override
    public void lockAndRequireOpenPeriod(long organizationId, Instant periodStart) {
        fence.lockOrganizationAndRequireNoClosingPeriod(organizationId);
        fence.lockOpenAt(organizationId, periodStart);
    }

    @Override
    public void lockAndRequireOpenPeriodsForAttempt(long organizationId, long attemptId) {
        // Global M6 lock order: organization admission, then BillingPeriods in
        // ascending effective-time order. No charge period means the existing
        // organization-level unknown-period admission remains in force.
        fence.lockOrganizationAndRequireNoClosingPeriod(organizationId);
        for (var periodStart : importChargePeriods.findContributingPeriodStarts(organizationId, attemptId).stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList()) {
            fence.lockOpenAt(organizationId, periodStart);
        }
    }

    @Override
    public void lockOpenAt(long organizationId, Instant effectiveAt) {
        fence.lockOpenAt(organizationId, effectiveAt);
    }

    @Override
    public void lockIfCoveredAndRequireOpenAt(long organizationId, Instant effectiveAt) {
        fence.lockIfCoveredAndRequireOpenAt(organizationId, effectiveAt);
    }
}
