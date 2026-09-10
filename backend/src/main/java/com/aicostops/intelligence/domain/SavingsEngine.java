package com.aicostops.intelligence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic counterfactual savings engine (M18 V3).
 *
 * <p>Candidates compare only within one explicitly shared logical model.
 * Historical usage replays through the existing pricing semantics; no model
 * ever computes an authoritative saving amount.
 */
public final class SavingsEngine {

    private SavingsEngine() {
    }

    public record PricedRate(long unitQuantity, BigDecimal unitPrice) {
    }

    public record Comparison(
            BigDecimal currentCost,
            BigDecimal candidateCost,
            BigDecimal potentialSaving,
            BigDecimal potentialSavingPercent) {
    }

    public static Comparison compare(
            long logicalModelId,
            long currentLogicalModelId,
            long candidateLogicalModelId,
            Map<String, Long> historicalUsage,
            Map<String, PricedRate> currentRates,
            Map<String, PricedRate> candidateRates) {
        Objects.requireNonNull(historicalUsage, "Historical usage is required");
        if (currentLogicalModelId != logicalModelId || candidateLogicalModelId != logicalModelId) {
            throw new IllegalArgumentException(
                    "Savings candidates must share the same logical-model semantics");
        }
        var current = replay(historicalUsage, currentRates);
        var candidate = replay(historicalUsage, candidateRates);
        var saving = current.subtract(candidate);
        var percent = current.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : saving.multiply(new BigDecimal("100")).divide(current, 4, RoundingMode.HALF_UP);
        return new Comparison(current, candidate, saving.max(BigDecimal.ZERO), percent.max(BigDecimal.ZERO));
    }

    static BigDecimal replay(Map<String, Long> usage, Map<String, PricedRate> rates) {
        var total = BigDecimal.ZERO;
        for (var entry : usage.entrySet()) {
            var rate = rates.get(entry.getKey());
            if (rate == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            total = total.add(BigDecimal.valueOf(entry.getValue())
                    .multiply(rate.unitPrice())
                    .divide(BigDecimal.valueOf(rate.unitQuantity()), 8, RoundingMode.HALF_UP));
        }
        return total;
    }
}
