package com.aicostops.gatewaysettlement.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact, frozen-rate Gateway cost calculation. */
public final class GatewaySettlementCostCalculator {

    private static final int RAW_PRECISION = 38;
    private static final int RAW_SCALE = 18;
    private static final int POSTED_SCALE = 8;
    private static final Set<String> SUPPORTED_DIMENSIONS = Set.of(
            "INPUT_TOKEN", "OUTPUT_TOKEN", "CACHED_INPUT_TOKEN", "REQUEST");

    public CostResult calculate(CostInput input) {
        if (input == null || input.rates() == null || input.quantities() == null) {
            throw invalid("Cost input, rates and quantities are required.");
        }
        if (input.currency() == null || !input.currency().matches("^[A-Z]{3}$")) {
            throw invalid("Settlement currency must be a three-letter uppercase code.");
        }
        var ratesByDimension = new HashMap<String, PricingRate>();
        for (var rate : input.rates()) {
            validateRate(rate);
            if (ratesByDimension.putIfAbsent(rate.dimensionCode(), rate) != null) {
                throw invalid("The frozen pricing version contains duplicate dimension rates.");
            }
        }
        for (var dimension : input.quantities().keySet()) {
            if (!SUPPORTED_DIMENSIONS.contains(dimension)) {
                throw invalid("Unsupported usage dimension: " + dimension);
            }
        }

        var raw = BigDecimal.ZERO;
        for (var rate : ratesByDimension.values()) {
            var quantity = input.quantities().get(rate.dimensionCode());
            if (quantity == null) {
                throw invalid("Missing required priced usage dimension: " + rate.dimensionCode());
            }
            validateQuantity(quantity, rate.dimensionCode());
            try {
                // Do not round a dimension before summing. The division is
                // exact or the input is rejected because DECIMAL(38,18)
                // cannot preserve the requested exact raw amount.
                var dimensionCost = quantity.multiply(rate.unitPrice())
                        .divide(BigDecimal.valueOf(rate.unitQuantity()));
                requireRawDecimal(dimensionCost);
                raw = raw.add(dimensionCost);
                requireRawDecimal(raw);
            } catch (ArithmeticException arithmeticFailure) {
                throw invalid("Frozen gateway cost is not exactly representable.");
            }
        }
        if (raw.signum() < 0) {
            throw invalid("Realtime Gateway incurred cost cannot be negative.");
        }

        final BigDecimal posted;
        try {
            posted = raw.setScale(POSTED_SCALE, RoundingMode.CEILING);
        } catch (ArithmeticException arithmeticFailure) {
            throw invalid("Posted gateway cost is outside DECIMAL(20,8).");
        }
        requirePostedDecimal(posted);
        var delta = posted.subtract(raw);
        requireRawDecimal(delta);
        return new CostResult(raw, posted, delta);
    }

    public CostResult calculate(String currency, Collection<PricingRate> rates,
            Map<String, BigDecimal> quantities) {
        return calculate(new CostInput(currency, rates == null ? null : List.copyOf(rates), quantities));
    }

    private static void validateRate(PricingRate rate) {
        if (rate == null || !SUPPORTED_DIMENSIONS.contains(rate.dimensionCode())) {
            throw invalid("Unsupported frozen pricing dimension.");
        }
        if (rate.unitQuantity() <= 0) {
            throw invalid("Pricing unit quantity must be greater than zero.");
        }
        if (rate.unitPrice() == null || rate.unitPrice().signum() < 0
                || rate.unitPrice().scale() > 8
                || integerDigits(rate.unitPrice()) > 12) {
            throw invalid("Pricing unit price must fit DECIMAL(20,8).");
        }
    }

    private static void validateQuantity(BigDecimal quantity, String dimension) {
        if (quantity.signum() < 0 || quantity.scale() > 8 || integerDigits(quantity) > 22) {
            throw invalid("Usage quantity for " + dimension + " must fit DECIMAL(30,8).");
        }
    }

    private static void requireRawDecimal(BigDecimal value) {
        if (value.scale() > RAW_SCALE || integerDigits(value) > RAW_PRECISION - RAW_SCALE
                || value.precision() > RAW_PRECISION) {
            throw invalid("Gateway raw cost is outside DECIMAL(38,18).");
        }
    }

    private static void requirePostedDecimal(BigDecimal value) {
        if (value.scale() > POSTED_SCALE || integerDigits(value) > 12
                || value.precision() > 20) {
            throw invalid("Posted gateway cost is outside DECIMAL(20,8).");
        }
    }

    private static int integerDigits(BigDecimal value) {
        return Math.max(0, value.precision() - value.scale());
    }

    private static GatewaySettlementCostException invalid(String detail) {
        return new GatewaySettlementCostException(detail);
    }

    public record CostInput(
            String currency,
            Collection<PricingRate> rates,
            Map<String, BigDecimal> quantities) {
        public CostInput {
            rates = rates == null ? null : List.copyOf(rates);
            quantities = quantities == null ? null : Map.copyOf(quantities);
        }
    }

    public record PricingRate(
            String dimensionCode,
            long unitQuantity,
            BigDecimal unitPrice) {
    }

    public record CostResult(
            BigDecimal calculatedAmountRaw,
            BigDecimal postedAmount,
            BigDecimal roundingDelta) {
    }

    public static final class GatewaySettlementCostException extends IllegalArgumentException {
        public GatewaySettlementCostException(String message) {
            super(message);
        }
    }
}
