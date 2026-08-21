package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;

/** Budget-owned state mutation seam for the M6 Close/Reopen coordinator. */
public interface BillingPeriodClosePort {
    void lockOrganizationAdmission(long organizationId);
    BillingPeriod lockPeriod(long organizationId, long periodId);
    BillingPeriod markClosing(long organizationId, long periodId, long expectedVersion, Instant now);
    BillingPeriod returnOpen(long organizationId, long periodId, long expectedVersion, Instant now);
    BillingPeriod markClosed(long organizationId, long periodId, long expectedVersion, Instant now);
    BillingPeriod reopen(long organizationId, long periodId, long expectedVersion, Instant now);
}
