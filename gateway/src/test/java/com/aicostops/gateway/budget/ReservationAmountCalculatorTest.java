package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M12 conservative reservation upper bounds (AIC-087 section 9 + frozen M12
 * design section 7). Input quantity is the fixed MiMo context ceiling
 * (1_048_576); output quantity is the exact effective max_completion_tokens
 * the Gateway already validated and sends upstream. Positive money always
 * rounds UP to scale 8, never down.
 */
class ReservationAmountCalculatorTest {

    private static final BigDecimal INPUT_PRICE = new BigDecimal("30.00000000");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("60.00000000");
    private static final long MILLION = 1_000_000L;

    @Test
    void mimoBaselineUpperBound() {
        // input: 1_048_576 * 30/1M = 31.45728
        // output: 8192 * 60/1M = 0.49152
        // total: 31.9488 (already scale 8)
        var amount = ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE)),
                8192);

        assertThat(amount).isEqualByComparingTo("31.94880000");
    }

    @Test
    void positiveAmountAlwaysRoundsUpNeverDown() {
        // output: 1 * 60/1M = 0.00006 (scale 5 -> pads, no rounding)
        var exact = ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE)),
                1);
        assertThat(exact).isEqualByComparingTo("31.45734000");

        // A unit price with 9 decimals forces CEILING, not truncation:
        // input: 1_048_576 * 0.000000031 = 0.032505856 -> 0.03250586
        var ceiling = ReservationAmountCalculator.calculate(
                List.of(rate("INPUT_TOKEN", 1_000_000_000L, new BigDecimal("31.00000000"))),
                0);
        assertThat(ceiling).isEqualByComparingTo("0.03250586");
        assertThat(ceiling.scale()).isEqualTo(8);
    }

    @Test
    void cachedInputTakesHigherNormalizedRate() {
        // INPUT 30/M vs CACHED 15/M: every conservative input token uses 30/M.
        var higher = ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("CACHED_INPUT_TOKEN", MILLION, new BigDecimal("15.00000000")),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE)),
                8192);
        assertThat(higher).isEqualByComparingTo("31.94880000");

        // CACHED 45/M beats INPUT 30/M: input uses 45/M.
        // input: 1_048_576 * 45/1M = 47.18592; output 0.49152 -> 47.67744
        var cachedHigher = ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("CACHED_INPUT_TOKEN", MILLION, new BigDecimal("45.00000000")),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE)),
                8192);
        assertThat(cachedHigher).isEqualByComparingTo("47.67744000");
    }

    @Test
    void requestDimensionIsAddedWhenPresent() {
        // REQUEST 0.0001/1 + baseline 31.9488 -> 31.9489
        var amount = ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE),
                        rate("REQUEST", 1L, new BigDecimal("0.00010000"))),
                8192);
        assertThat(amount).isEqualByComparingTo("31.94890000");
    }

    @Test
    void missingInputTokenFailsClosed() {
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE)), 100))
                .isInstanceOf(ReservationBoundException.class)
                .satisfies(ex -> assertThat(((ReservationBoundException) ex).failClosed())
                        .isTrue());
    }

    @Test
    void positiveOutputLimitRequiresOutputToken() {
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(rate("INPUT_TOKEN", MILLION, INPUT_PRICE)), 100))
                .isInstanceOf(ReservationBoundException.class);
    }

    @Test
    void zeroOutputLimitSkipsOutputDimension() {
        var amount = ReservationAmountCalculator.calculate(
                List.of(rate("INPUT_TOKEN", MILLION, INPUT_PRICE)), 0);
        assertThat(amount).isEqualByComparingTo("31.45728000");
    }

    @Test
    void unknownDimensionFailsClosed() {
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE),
                        rate("IMAGE_UNIT", 1L, BigDecimal.ONE)),
                100))
                .isInstanceOf(ReservationBoundException.class);
    }

    @Test
    void nonRepresentableOrNonPositiveFailsClosed() {
        // Zero input price -> reservation 0 -> fail closed for budget control.
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(rate("INPUT_TOKEN", MILLION, BigDecimal.ZERO)), 0))
                .isInstanceOf(ReservationBoundException.class);

        // unit_quantity 0 is not a valid pricing rate.
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(rate("INPUT_TOKEN", 0L, INPUT_PRICE)), 0))
                .isInstanceOf(ReservationBoundException.class);

        // unit_quantity must be a power of ten within 1..1_000_000_000.
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(rate("INPUT_TOKEN", 3L, INPUT_PRICE)), 0))
                .isInstanceOf(ReservationBoundException.class);
    }

    @Test
    void negativeOutputLimitIsRejected() {
        assertThatThrownBy(() -> ReservationAmountCalculator.calculate(
                List.of(
                        rate("INPUT_TOKEN", MILLION, INPUT_PRICE),
                        rate("OUTPUT_TOKEN", MILLION, OUTPUT_PRICE)),
                -1))
                .isInstanceOf(ReservationBoundException.class);
    }

    private static ReservationAmountCalculator.PricingRate rate(
            String dimension, long unitQuantity, BigDecimal unitPrice) {
        return new ReservationAmountCalculator.PricingRate(dimension, unitQuantity, unitPrice);
    }
}
