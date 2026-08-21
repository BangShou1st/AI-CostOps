package com.aicostops.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ReconciliationCase(
        long id,
        long organizationId,
        long reconciliationRunId,
        long providerAccountId,
        String currency,
        ReconciliationCaseType caseType,
        BigDecimal externalAmount,
        BigDecimal internalAmount,
        BigDecimal differenceAmount,
        long externalRowCount,
        long internalRowCount,
        ReconciliationCaseStatus status,
        String reasonCode,
        String resolutionNote,
        Long resolvedByMemberId,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {
}
