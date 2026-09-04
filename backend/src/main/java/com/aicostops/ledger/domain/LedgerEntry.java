package com.aicostops.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntry(
        long id,
        long organizationId,
        long postingId,
        int entryIndex,
        LedgerEntryType entryType,
        BigDecimal amount,
        String currency,
        Long projectId,
        Long costCenterId,
        Long teamId,
        Long budgetId,
        Long sourceChargeFactId,
        Long sourceExpenseClaimId,
        Long sourceGatewaySettlementId,
        Long allocationLineId,
        Long correctionGroupId,
        Long reversesEntryId,
        Instant createdAt) {

    public Long targetId() {
        if (projectId != null) {
            return projectId;
        }
        if (costCenterId != null) {
            return costCenterId;
        }
        return teamId;
    }

    public String targetType() {
        if (projectId != null) {
            return "PROJECT";
        }
        if (costCenterId != null) {
            return "COST_CENTER";
        }
        return "TEAM";
    }
}
