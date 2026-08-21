package com.aicostops.reconciliation.domain;

import java.time.Instant;

public record PeriodCloseRun(
        long id,
        long organizationId,
        long billingPeriodId,
        long closeGeneration,
        int attemptNo,
        PeriodCloseRunStatus status,
        Long reconciliationRunId,
        long startedByMemberId,
        Instant startedAt,
        Instant finishedAt,
        String errorCode,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt) {
}
