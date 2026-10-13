package com.aicostops.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic forecast (RUN_VERSION 4): DAMPED_HOLT primary with a bounded
 * RECENT_RUN_RATE fallback. Output is a DERIVED_ESTIMATE, never Ledger truth.
 *
 * <p>Eligibility: a signed ledger net below or equal to zero cannot support a positive
 * future-usage extrapolation. Reversal-dominated history yields no forecast at all
 * ({@link ForecastUnavailableException}), while an all-zero observed history legitimately
 * projects zero future spend. Negative projected amounts can never leave this engine.
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
        for (var i = 1; i < completedBuckets.size(); i++) {
            if (!completedBuckets.get(i).date().equals(completedBuckets.get(i - 1).date().plusDays(1))) {
                throw new IllegalArgumentException("Forecast buckets must be consecutive and ordered");
            }
        }
        if (observedThrough == null || !completedBuckets.get(completedBuckets.size() - 1).date().equals(observedThrough)) {
            throw new IllegalArgumentException("Forecast history must end at observedThrough");
        }
        // Unknown days break evidence continuity; zero-valued observed days do not.
        // Never compress sparse dates into an apparently continuous daily series.
        var firstObserved = completedBuckets.size();
        while (firstObserved > 0 && completedBuckets.get(firstObserved - 1).observed()) {
            firstObserved--;
        }
        completedBuckets = completedBuckets.subList(firstObserved, completedBuckets.size());
        if (completedBuckets.size() < MIN_FALLBACK_BUCKETS) {
            throw new IllegalArgumentException("Insufficient history for a forecast");
        }
        requireExtrapolatableHistory(completedBuckets);
        if (completedBuckets.size() >= MIN_DAMPED_HOLT_BUCKETS) {
            var daily = dampedHoltDaily(completedBuckets);
            var projected = daily.multiply(BigDecimal.valueOf(Math.max(remainingDays, 0)));
            return new Forecast(projected, currency, "DAMPED_HOLT", completedBuckets.size(),
                    completedBuckets.size() >= 21 ? "HIGH" : "MEDIUM", observedThrough);
        }
        // The fallback extrapolates from the trailing window only, so eligibility must hold for
        // that exact window too: a positive whole-history net can hide a reversal-dominated
        // tail whose run rate would still produce a negative forecast.
        var window = completedBuckets.subList(
                Math.max(0, completedBuckets.size() - FALLBACK_WINDOW), completedBuckets.size());
        requireExtrapolatableHistory(window);
        var sum = window.stream().map(CostAnomalyEngine.DailyBucket::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var daily = sum.divide(BigDecimal.valueOf(window.size()), 8, RoundingMode.HALF_UP);
        return new Forecast(daily.multiply(BigDecimal.valueOf(Math.max(remainingDays, 0))),
                currency, "RECENT_RUN_RATE", completedBuckets.size(), "LOW", observedThrough);
    }

    /**
     * A reversal-dominated window (net below zero, or netting to zero while containing signed
     * negative observations) cannot be extrapolated into a future-usage estimate in either
     * direction: negative amounts are meaningless, positive ones are unsupported. An observed
     * all-zero window is a legitimate zero forecast, distinct from unavailable.
     */
    private static void requireExtrapolatableHistory(List<CostAnomalyEngine.DailyBucket> buckets) {
        var net = buckets.stream().map(CostAnomalyEngine.DailyBucket::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (net.compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        var hasNegative = buckets.stream().map(CostAnomalyEngine.DailyBucket::amount)
                .anyMatch(a -> a.compareTo(BigDecimal.ZERO) < 0);
        if (hasNegative) {
            throw new ForecastUnavailableException(
                    "Observed net " + net.toPlainString() + " cannot support a future-usage forecast");
        }
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

    /** Eligibility signal: the history cannot support any future-usage estimate. */
    public static final class ForecastUnavailableException extends RuntimeException {
        public ForecastUnavailableException(String message) {
            super(message);
        }
    }
}
