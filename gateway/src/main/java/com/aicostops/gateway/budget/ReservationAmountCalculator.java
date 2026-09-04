package com.aicostops.gateway.budget;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * M12 conservative reservation upper-bound calculator (AIC-087 section 9).
 *
 * <p>Input quantity is the fixed MiMo context ceiling (1_048_576 tokens); no
 * chars/token estimate is used. Output quantity is the exact effective
 * max_completion_tokens the Gateway already validated and sends upstream.
 * When CACHED_INPUT_TOKEN exists, every conservative input token uses the
 * higher normalized unit rate of INPUT_TOKEN vs CACHED_INPUT_TOKEN; cache
 * hits are never predicted. Positive money always rounds UP to scale 8.
 *
 * <p>Pure function: no DB, no Redis, no clock. Anything that cannot be safely
 * bounded (missing INPUT_TOKEN, missing OUTPUT_TOKEN for a positive output
 * limit, unknown dimension, non-positive or non-representable result) fails
 * closed via {@link ReservationBoundException}.
 */
public final class ReservationAmountCalculator {

    /** Frozen conservative MiMo context token ceiling. */
    public static final long INPUT_RESERVATION_TOKENS = 1_048_576L;

    private static final Set<String> KNOWN_DIMENSIONS = Set.of(
            "INPUT_TOKEN", "OUTPUT_TOKEN", "CACHED_INPUT_TOKEN", "REQUEST");

    private static final MathContext MATH = MathContext.DECIMAL128;

    private ReservationAmountCalculator() {
    }

    /**
     * Calculates the conservative reservation amount for one route attempt.
     *
     * @param rates pricing rates of the frozen Pricing Version
     * @param effectiveMaxOutputTokens exact effective output ceiling (&gt;= 0)
     * @return scale-8 reservation amount, rounded UP
     * @throws ReservationBoundException when no safe bound exists (fail closed)
     */
    public static BigDecimal calculate(List<PricingRate> rates, long effectiveMaxOutputTokens) {
        if (rates == null || rates.isEmpty()) {
            throw new ReservationBoundException("No pricing rates are available for reservation");
        }
        if (effectiveMaxOutputTokens < 0) {
            throw new ReservationBoundException("Effective output limit must not be negative");
        }
        for (var rate : rates) {
            if (!KNOWN_DIMENSIONS.contains(rate.dimensionCode())) {
                throw new ReservationBoundException(
                        "Unsupported pricing dimension: " + rate.dimensionCode());
            }
            if (!isSupportedUnitQuantity(rate.unitQuantity())) {
                throw new ReservationBoundException(
                        "Unsupported unit quantity for " + rate.dimensionCode());
            }
            if (rate.unitPrice() == null || rate.unitPrice().signum() < 0) {
                throw new ReservationBoundException(
                        "Invalid unit price for " + rate.dimensionCode());
            }
        }

        var inputRate = normalizedRate(rates, "INPUT_TOKEN");
        if (inputRate == null) {
            throw new ReservationBoundException("INPUT_TOKEN pricing is required for reservation");
        }
        var cachedRate = normalizedRate(rates, "CACHED_INPUT_TOKEN");
        var effectiveInputRate = cachedRate != null && cachedRate.compareTo(inputRate) > 0
                ? cachedRate
                : inputRate;

        var outputRate = normalizedRate(rates, "OUTPUT_TOKEN");
        if (effectiveMaxOutputTokens > 0 && outputRate == null) {
            throw new ReservationBoundException(
                    "OUTPUT_TOKEN pricing is required for a positive output limit");
        }
        var requestRate = normalizedRate(rates, "REQUEST");

        var raw = effectiveInputRate.multiply(BigDecimal.valueOf(INPUT_RESERVATION_TOKENS), MATH);
        if (effectiveMaxOutputTokens > 0) {
            raw = raw.add(outputRate.multiply(BigDecimal.valueOf(effectiveMaxOutputTokens), MATH));
        }
        if (requestRate != null) {
            raw = raw.add(requestRate);
        }

        if (raw.signum() <= 0) {
            throw new ReservationBoundException("Reservation amount must be positive");
        }
        BigDecimal reserved;
        try {
            reserved = raw.setScale(8, RoundingMode.CEILING);
        } catch (ArithmeticException ex) {
            throw new ReservationBoundException("Reservation amount is not representable", ex);
        }
        if (reserved.signum() <= 0) {
            throw new ReservationBoundException("Reservation amount must be positive");
        }
        return reserved;
    }

    private static BigDecimal normalizedRate(List<PricingRate> rates, String dimension) {
        for (var rate : rates) {
            if (rate.dimensionCode().equals(dimension)) {
                return rate.unitPrice()
                        .divide(BigDecimal.valueOf(rate.unitQuantity()), MATH);
            }
        }
        return null;
    }

    private static boolean isSupportedUnitQuantity(long unitQuantity) {
        return switch ((int) unitQuantity) {
            case 1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000 -> true;
            default -> false;
        };
    }

    public record PricingRate(String dimensionCode, long unitQuantity, BigDecimal unitPrice) {
    }
}
