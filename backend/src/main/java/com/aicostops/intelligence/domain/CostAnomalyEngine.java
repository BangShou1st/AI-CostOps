package com.aicostops.intelligence.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic explainable anomaly detection (M18 V3).
 *
 * <p>Frozen baseline: 28 completed daily buckets, minimum 14 buckets,
 * median baseline, MAD dispersion, robust z-score. An anomaly requires the
 * statistical score AND the percentage delta AND the absolute materiality
 * threshold. Money stays {@link BigDecimal}; the z-score is a non-financial
 * derived signal and may use {@code double}.
 */
public final class CostAnomalyEngine {

    public static final int LOOKBACK_DAYS = 28;
    public static final int MIN_HISTORY_BUCKETS = 14;
    public static final double Z_THRESHOLD = 3.0;
    public static final BigDecimal PCT_THRESHOLD = new BigDecimal("20");

    private CostAnomalyEngine() {
    }

    public record DailyBucket(LocalDate date, BigDecimal amount, String currency) {
    }

    public record Contribution(String dimension, String key, BigDecimal deltaAmount) {
    }

    public record Anomaly(
            String grainType,
            String grainKey,
            String currency,
            BigDecimal observedAmount,
            BigDecimal baselineAmount,
            BigDecimal deltaAmount,
            BigDecimal deltaPercent,
            double robustZScore,
            List<Contribution> drivers) {
    }

    /**
     * Evaluates the latest completed bucket against its lookback history.
     * Returns an empty list when history is insufficient or no gate fires.
     */
    public static List<Anomaly> detect(
            String grainType,
            String grainKey,
            List<DailyBucket> completedBuckets,
            List<Contribution> contributions,
            BigDecimal materialityThreshold) {
        if (completedBuckets == null || completedBuckets.size() < MIN_HISTORY_BUCKETS + 1) {
            return List.of();
        }
        var history = completedBuckets.size() > LOOKBACK_DAYS + 1
                ? completedBuckets.subList(completedBuckets.size() - LOOKBACK_DAYS - 1, completedBuckets.size())
                : completedBuckets;
        if (history.size() < MIN_HISTORY_BUCKETS + 1) {
            return List.of();
        }
        var observed = history.get(history.size() - 1);
        var baselineBuckets = history.subList(0, history.size() - 1);
        var currency = observed.currency();
        for (var bucket : baselineBuckets) {
            if (!currency.equals(bucket.currency())) {
                throw new IllegalArgumentException("Cross-currency buckets must never be mixed");
            }
        }
        var amounts = baselineBuckets.stream().map(DailyBucket::amount).sorted().toList();
        var median = median(amounts);
        var mad = mad(amounts, median);
        var delta = observed.amount().subtract(median);
        final double zScore;
        if (mad.compareTo(BigDecimal.ZERO) == 0) {
            zScore = delta.compareTo(BigDecimal.ZERO) == 0 ? 0.0 : Math.copySign(10.0, delta.doubleValue());
        } else {
            zScore = 0.6745 * delta.divide(mad, MathContext.DECIMAL64).doubleValue();
        }
        var deltaPercent = median.compareTo(BigDecimal.ZERO) == 0
                ? (delta.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : new BigDecimal("100"))
                : delta.multiply(new BigDecimal("100"))
                        .divide(median.abs(), 4, RoundingMode.HALF_UP);
        if (Math.abs(zScore) < Z_THRESHOLD) {
            return List.of();
        }
        if (deltaPercent.abs().compareTo(PCT_THRESHOLD) < 0) {
            return List.of();
        }
        if (delta.abs().compareTo(materialityThreshold) < 0) {
            return List.of();
        }
        var drivers = new ArrayList<>(contributions == null ? List.<Contribution>of() : contributions);
        drivers.sort(Comparator.comparing(Contribution::deltaAmount).reversed());
        return List.of(new Anomaly(grainType, grainKey, currency, observed.amount(), median,
                delta, deltaPercent, zScore, List.copyOf(drivers)));
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        var size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                .divide(new BigDecimal("2"), MathContext.DECIMAL64);
    }

    private static BigDecimal mad(List<BigDecimal> sorted, BigDecimal median) {
        var deviations = sorted.stream().map(v -> v.subtract(median).abs()).sorted().toList();
        return median(deviations);
    }
}
