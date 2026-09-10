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

    /**
     * Counterfactual replay over the shared source-route usage vector. Quantities stay
     * {@link BigDecimal} end to end (usage is DECIMAL(30,8); only {@code unit_quantity} is an
     * integral BIGINT rate scalar). Dimension math follows the settlement pricing engine operand
     * order (quantity x unit_price / unit_quantity) at raw precision (scale 18); unlike the
     * authoritative settlement path, which rejects non-terminating quotients, this derived
     * estimate rounds half-up so one candidate rate can never fail a whole analysis run.
     */
    public static Comparison compare(
            long logicalModelId,
            long currentLogicalModelId,
            long candidateLogicalModelId,
            Map<String, BigDecimal> historicalUsage,
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

    public static BigDecimal replay(Map<String, BigDecimal> usage, Map<String, PricedRate> rates) {
        var total = BigDecimal.ZERO;
        for (var entry : usage.entrySet()) {
            var rate = rates.get(entry.getKey());
            var quantity = entry.getValue();
            if (rate == null || quantity == null || quantity.signum() <= 0) {
                continue;
            }
            total = total.add(quantity.multiply(rate.unitPrice())
                    .divide(BigDecimal.valueOf(rate.unitQuantity()), 18, RoundingMode.HALF_UP));
        }
        return total;
    }
}
