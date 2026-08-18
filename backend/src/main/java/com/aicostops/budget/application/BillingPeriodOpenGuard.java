package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;

/**
 * Reusable application-level BillingPeriod OPEN guard.
 *
 * <p>Every future financial write — commitment activation, ledger posting,
 * expense posting — resolves the covering billing period for the
 * organization and transaction time and requires it to be OPEN instead of
 * duplicating the resolution/status logic in each posting service.
 *
 * <p>Resolution semantics are the half-open range {@code [period_start,
 * period_end)}: the start boundary is included, the end boundary is
 * excluded. No covering period (including a query with a wrong
 * organization) yields a state-conflict problem; a covering period that is
 * not OPEN yields a {@code PERIOD_NOT_OPEN} problem; more than one covering
 * period (an overlapping period identity) fails closed with a
 * {@code STATE_CONFLICT} problem and never returns a picked winner.
 */
public interface BillingPeriodOpenGuard {

    /**
     * Resolves the billing period covering {@code transactionTime} in the
     * organization and requires it to be OPEN.
     *
     * @return the covering OPEN period
     * @throws com.aicostops.shared.web.DomainException with code
     *         {@code STATE_CONFLICT} when no period covers the time or when
     *         several (overlapping) periods cover it, or code
     *         {@code PERIOD_NOT_OPEN} when the single covering period is
     *         CLOSING or CLOSED
     */
    BillingPeriod requireOpen(long organizationId, Instant transactionTime);
}