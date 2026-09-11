package com.aicostops.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic forecast (M18 V3): DAMPED_HOLT primary with a bounded
 * RECENT_RUN_RATE fallback. Output is a DERIVED_ESTIMATE, never Ledger truth.
 */
public final class ForecastEngine {

    public static final int MIN_DAMPED_HOLT_BUCKETS = 14;
    public static final int MIN_FALLBACK_BUCKETS = 3;
    public static final int FALLBACK_WINDOW = 7;
    private static final double ALPHA = 0.4;
    private static final double BETA = 0.3;
    private static final double PHI = 0.9;

    private ForecastEngine() {
    }

    public record Forecast(
            BigDecimal projectedAmount,
            String currency,
            String method,
            int historyBucketCount,
            String confidence,
            LocalDate observedThrough) {
    }

    /** Projects total usage for the remaining days of the period. */
    public static Forecast forecast(
            List<CostAnomalyEngine.DailyBucket> completedBuckets,
            int remainingDays,
            LocalDate observedThrough) {
        if (completedBuckets == null || completedBuckets.isEmpty()) {
            throw new IllegalArgumentException("Forecast history is required");
        }
        var currency = completedBuckets.get(0).currency();
        for (var bucket : completedBuckets) {
            if (!currency.equals(bucket.currency())) {
                throw new IllegalArgumentException("Cross-currency buckets must never be mixed");
            }
        }
        if (remainingDays < 0) {
            throw new IllegalArgumentException("Remaining days must be zero or greater");
        }
        if (completedBuckets.size() >= MIN_DAMPED_HOLT_BUCKETS) {
            var daily = dampedHoltDaily(completedBuckets);
            var projected = daily.multiply(BigDecimal.valueOf(Math.max(remainingDays, 0)));
            return new Forecast(projected, currency, "DAMPED_HOLT", completedBuckets.size(),
                    completedBuckets.size() >= 21 ? "HIGH" : "MEDIUM", observedThrough);
        }
        if (completedBuckets.size() >= MIN_FALLBACK_BUCKETS) {
            var window = completedBuckets.subList(
                    Math.max(0, completedBuckets.size() - FALLBACK_WINDOW), completedBuckets.size());
            var sum = window.stream().map(CostAnomalyEngine.DailyBucket::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var daily = sum.divide(BigDecimal.valueOf(window.size()), 8, RoundingMode.HALF_UP);
            return new Forecast(daily.multiply(BigDecimal.valueOf(Math.max(remainingDays, 0))),
                    currency, "RECENT_RUN_RATE", completedBuckets.size(), "LOW", observedThrough);
        }
        throw new IllegalArgumentException("Insufficient history for a forecast");
    }

    static BigDecimal dampedHoltDaily(List<CostAnomalyEngine.DailyBucket> buckets) {
        double level = buckets.get(0).amount().doubleValue();
        double trend = 0.0;
        for (var i = 1; i < buckets.size(); i++) {
            var observed = buckets.get(i).amount().doubleValue();
            var previousLevel = level;
            level = ALPHA * observed + (1 - ALPHA) * (previousLevel + PHI * trend);
            trend = BETA * (level - previousLevel) + (1 - BETA) * PHI * trend;
        }
        var oneStep = level + PHI * trend;
        return BigDecimal.valueOf(Math.max(oneStep, 0.0));
    }
}
