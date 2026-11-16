package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;

/**
 * Period-fence for financial writes that must be admitted only while the
 * covering period is OPEN.
 *
 * <p>The period read itself takes a shared row lock. Financial writers may
 * proceed concurrently; Close/Reopen still take the exclusive lock and
 * therefore wait for every in-flight financial transaction. Period close and
 * reopen mutations use their own exclusive port, not this fence.
 */
public interface BillingPeriodFinancialWriteFence {
    BillingPeriod lockOpenAt(long organizationId, Instant effectiveAt);
    BillingPeriod lockById(long organizationId, long billingPeriodId);
    BillingPeriod lockOpenById(long organizationId, long billingPeriodId);
    BillingPeriod lockForReconciliationAdmission(long organizationId, long billingPeriodId);
    void lockIfCoveredAndRequireOpenAt(long organizationId, Instant effectiveAt);
    void lockOrganizationAndRequireNoClosingPeriod(long organizationId);
    boolean hasClosingPeriod(long organizationId);
}
