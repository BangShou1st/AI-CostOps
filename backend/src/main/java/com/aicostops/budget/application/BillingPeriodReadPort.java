package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import java.util.List;

/** Permission-neutral owner-module read seam for cross-module period identity. */
public interface BillingPeriodReadPort {
    BillingPeriod findById(long organizationId, long billingPeriodId);

    /**
     * All periods of the organization, newest first. Permission-neutral: the
     * caller enforces its own read permission and only exposes period
     * identity (id + status), never budget-sensitive fields.
     */
    List<BillingPeriod> listByOrganization(long organizationId);
}
