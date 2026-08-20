package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;

/** Row-lock fence for financial writes that must serialize against Close. */
public interface BillingPeriodFinancialWriteFence {
    BillingPeriod lockOpenAt(long organizationId, Instant effectiveAt);
    BillingPeriod lockOpenById(long organizationId, long billingPeriodId);
    void lockOrganizationAndRequireNoClosingPeriod(long organizationId);
    boolean hasClosingPeriod(long organizationId);
}
