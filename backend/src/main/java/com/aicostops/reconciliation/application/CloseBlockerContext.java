package com.aicostops.reconciliation.application;

import java.time.Instant;

public record CloseBlockerContext(
        long organizationId,
        long billingPeriodId,
        Instant periodStart,
        Instant periodEnd) {
}
