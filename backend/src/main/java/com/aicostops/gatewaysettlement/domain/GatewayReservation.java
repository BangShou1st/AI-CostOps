package com.aicostops.gatewaysettlement.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Settlement-facing read model of the already-governed Gateway reservation. */
public record GatewayReservation(
        long id,
        long organizationId,
        long requestId,
        long routeAttemptId,
        long billingPeriodId,
        long budgetId,
        String financialScopeType,
        long financialScopeId,
        String currency,
        BigDecimal reservedAmount,
        Long commitmentId,
        BigDecimal commitmentBackedAmount,
        String status,
        long version,
        Instant expiresAt,
        Instant finalizedAt) {
}
