package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;

/** Permission-neutral owner-module read seam for cross-module period identity. */
public interface BillingPeriodReadPort {
    BillingPeriod findById(long organizationId, long billingPeriodId);
}
