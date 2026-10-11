package com.aicostops.intelligence.application;

import java.math.BigDecimal;

/**
 * Deterministic budget-risk evidence frozen into Advisor snapshots (M18 V3).
 *
 * <p>The model explains these precomputed values and must never recompute projected spend or the
 * risk classification. Reservations appear in {@code immediateExposure} only and are never
 * extrapolated into {@code forecastFutureUsage}. This is a cross-module consumption contract, so
 * it lives outside the service that builds it.
 */
public record BudgetRiskEvidence(BigDecimal actual, BigDecimal committed, BigDecimal reservations,
        BigDecimal forecastFutureUsage, BigDecimal immediateExposure, BigDecimal projectedPeriodEnd,
        BigDecimal budgetTotal, String currency, String risk) {
}
