package com.aicostops.budget.domain;

import java.time.Instant;

/**
 * Immutable billing period state. The period covers the half-open time range
 * {@code [periodStart, periodEnd)}. {@code closeGeneration} counts Close /
 * Reopen generations (AIC-058), and {@code version} is the optimistic-lock
 * counter of the period row.
 */
public record BillingPeriod(
        long id,
        long organizationId,
        Instant periodStart,
        Instant periodEnd,
        BillingPeriodStatus status,
        long closeGeneration,
        Instant closingStartedAt,
        Instant closedAt,
        Instant reopenedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public boolean covers(Instant at) {
        return !at.isBefore(periodStart) && at.isBefore(periodEnd);
    }
}