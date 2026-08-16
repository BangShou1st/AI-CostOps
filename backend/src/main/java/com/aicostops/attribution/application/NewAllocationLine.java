package com.aicostops.attribution.application;

import java.math.BigDecimal;

/** Draft of one allocation line; money and currency are guarded before insert. */
public record NewAllocationLine(
        long organizationId,
        long decisionId,
        int lineIndex,
        BigDecimal allocatedAmount,
        String currency,
        Long projectId,
        Long costCenterId,
        Long teamId) {
}
