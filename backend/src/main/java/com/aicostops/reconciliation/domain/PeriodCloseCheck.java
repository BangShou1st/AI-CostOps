package com.aicostops.reconciliation.domain;

import java.time.Instant;

public record PeriodCloseCheck(
        long id,
        long organizationId,
        long periodCloseRunId,
        CloseBlockerCode blockerCode,
        PeriodCloseCheckResult result,
        long itemCount,
        String summaryJson,
        Instant evaluatedAt,
        Instant createdAt) {
}
