package com.aicostops.attribution.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One allocation line of a decision: exact-scale money and exactly one target
 * (project, cost center, or team).
 */
public record AllocationLine(
        long id,
        long organizationId,
        long decisionId,
        int lineIndex,
        BigDecimal allocatedAmount,
        String currency,
        Long projectId,
        Long costCenterId,
        Long teamId,
        Instant createdAt) {
}
