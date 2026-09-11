package com.aicostops.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic budget risk projection (M18 V3).
 *
 * <p>Immediate Exposure = actual + outstanding commitments + active
 * reservations. Projected Period End = actual + outstanding commitments +
 * forecast future usage. Reservations are never extrapolated into the
 * remaining-period forecast.
 */
public final class BudgetRiskService {

    private BudgetRiskService() {
    }

    public record Assessment(
            BigDecimal immediateExposure,
            BigDecimal projectedPeriodEnd,
            BigDecimal budgetTotal,
            String currency,
            String risk) {
    }

    public static Assessment assess(
            BigDecimal actual,
            BigDecimal outstandingCommitments,
            BigDecimal activeReservations,
            BigDecimal forecastFutureUsage,
            BigDecimal budgetTotal,
            String currency) {
        for (var amount : new BigDecimal[] {
                actual, outstandingCommitments, activeReservations, forecastFutureUsage, budgetTotal }) {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Budget components must be zero or greater");
            }
        }
        var immediate = actual.add(outstandingCommitments).add(activeReservations);
        var projected = actual.add(outstandingCommitments).add(forecastFutureUsage);
        var risk = classify(immediate, projected, budgetTotal);
        return new Assessment(immediate, projected, budgetTotal, currency, risk);
    }

    private static String classify(BigDecimal immediate, BigDecimal projected, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return immediate.compareTo(BigDecimal.ZERO) > 0 || projected.compareTo(BigDecimal.ZERO) > 0
                    ? "OVER_BUDGET" : "LOW";
        }
        if (immediate.compareTo(total) > 0 || projected.compareTo(total) > 0) {
            return "OVER_BUDGET";
        }
        var immediateRatio = immediate.divide(total, 4, RoundingMode.HALF_UP);
        var projectedRatio = projected.divide(total, 4, RoundingMode.HALF_UP);
        var worst = immediateRatio.max(projectedRatio);
        if (worst.compareTo(new BigDecimal("0.90")) >= 0) {
            return "HIGH";
        }
        if (worst.compareTo(new BigDecimal("0.75")) >= 0) {
            return "WATCH";
        }
        return "LOW";
    }
}
