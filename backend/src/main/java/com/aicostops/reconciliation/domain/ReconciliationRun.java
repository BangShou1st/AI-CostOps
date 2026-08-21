package com.aicostops.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ReconciliationRun(
        long id,
        long organizationId,
        long billingPeriodId,
        ReconciliationRunStatus status,
        String algorithmVersion,
        BigDecimal toleranceAmount,
        String basisHash,
        String summaryJson,
        long createdByMemberId,
        Instant startedAt,
        Instant finishedAt,
        String errorCode,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt) {
}
