package com.aicostops.gatewaysettlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable read model of the backend-owned settlement row. */
public record GatewaySettlement(
        long id,
        long organizationId,
        String settlementKey,
        long requestId,
        long routeAttemptId,
        long usageFactId,
        Long reservationId,
        long billingPeriodId,
        String financialScopeType,
        long financialScopeId,
        long providerAccountId,
        long providerModelId,
        long pricingVersionId,
        String currency,
        BigDecimal calculatedAmountRaw,
        BigDecimal postedAmount,
        BigDecimal roundingDelta,
        GatewaySettlementStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastErrorCode,
        Long ledgerPostingId,
        Instant createdAt,
        Instant settledAt,
        Instant reconciliationRequiredAt,
        Instant updatedAt) {

    public boolean isSettled() {
        return status == GatewaySettlementStatus.SETTLED;
    }
}
