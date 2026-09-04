package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gatewaysettlement.application.GatewaySettlementCostCalculator;
import com.aicostops.gatewaysettlement.application.GatewaySettlementCostCalculator.CostInput;
import com.aicostops.gatewaysettlement.application.GatewaySettlementCostCalculator.PricingRate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewaySettlementCostCalculatorTest {

    private final GatewaySettlementCostCalculator calculator = new GatewaySettlementCostCalculator();

    @Test
    void calculatesInputAndOutputWithExactBigDecimalMath() {
        var result = calculator.calculate(new CostInput("USD", List.of(
                rate("INPUT_TOKEN", 1_000_000, "30.00000000"),
                rate("OUTPUT_TOKEN", 1_000_000, "60.00000000")),
                Map.of("INPUT_TOKEN", decimal("1000000"), "OUTPUT_TOKEN", decimal("500000"))));

        assertThat(result.calculatedAmountRaw()).isEqualByComparingTo("60.00000000000000");
        assertThat(result.postedAmount()).isEqualByComparingTo("60.00000000");
        assertThat(result.roundingDelta()).isEqualByComparingTo("0.00000000000000");
    }

    @Test
    void sumsMultipleDimensionsBeforeQuantizing() {
        var result = calculator.calculate(new CostInput("USD", List.of(
                rate("INPUT_TOKEN", 3, "1.00000000"),
                rate("OUTPUT_TOKEN", 2, "1.00000000")),
                Map.of("INPUT_TOKEN", decimal("3"), "OUTPUT_TOKEN", decimal("1"))));

        assertThat(result.calculatedAmountRaw()).isEqualByComparingTo("1.50000000");
        assertThat(result.postedAmount()).isEqualByComparingTo("1.50000000");
    }

    @Test
    void roundsPositiveNonScaleEightAmountUpAndKeepsDelta() {
        var result = calculator.calculate(new CostInput("USD", List.of(
                rate("REQUEST", 400_000_000, "1.00000000")),
                Map.of("REQUEST", decimal("1"))));

        assertThat(result.calculatedAmountRaw()).isEqualByComparingTo("0.0000000025");
        assertThat(result.postedAmount()).isEqualByComparingTo("0.00000001");
        assertThat(result.roundingDelta()).isEqualByComparingTo("0.0000000075");
    }

    @Test
    void tinyPositiveCostNeverRoundsToZero() {
        var result = calculator.calculate(new CostInput("USD", List.of(
                rate("REQUEST", 1_000_000_000, "1.00000000")),
                Map.of("REQUEST", decimal("1"))));

        assertThat(result.calculatedAmountRaw()).isEqualByComparingTo("0.000000001");
        assertThat(result.postedAmount()).isEqualByComparingTo("0.00000001");
    }

    @Test
    void exactScaleEightAmountHasZeroDelta() {
        var result = calculator.calculate(new CostInput("USD", List.of(
                rate("REQUEST", 1, "1.23456789")),
                Map.of("REQUEST", decimal("1"))));

        assertThat(result.postedAmount().scale()).isEqualTo(8);
        assertThat(result.roundingDelta()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void missingAndUnsupportedDimensionsFailClosed() {
        assertThatThrownBy(() -> calculator.calculate(new CostInput("USD", List.of(
                rate("INPUT_TOKEN", 1, "1.00000000")), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required priced usage dimension");

        assertThatThrownBy(() -> calculator.calculate(new CostInput("USD", List.of(
                rate("NOT_SUPPORTED", 1, "1.00000000")), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported frozen pricing dimension");
    }

    @Test
    void invalidUnitQuantityAndOverflowFailSafely() {
        assertThatThrownBy(() -> calculator.calculate(new CostInput("USD", List.of(
                rate("REQUEST", 0, "1.00000000")), Map.of("REQUEST", decimal("1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit quantity");

        assertThatThrownBy(() -> calculator.calculate(new CostInput("USD", List.of(
                rate("REQUEST", 1, "999999999999.99999999")),
                Map.of("REQUEST", decimal("9999999999999999999999")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECIMAL(38,18)");
    }

    @Test
    void calculatorDoesNotResolveOrDependOnPricingStatus() {
        // Only the rates handed to the calculator are authoritative. A caller
        // can pass frozen V1 even if a V2 later becomes ACTIVE.
        var result = calculator.calculate(new CostInput("USD", List.of(
                rate("REQUEST", 1, "1.00000000")), Map.of("REQUEST", decimal("2"))));

        assertThat(result.postedAmount()).isEqualByComparingTo("2.00000000");
    }

    private static PricingRate rate(String dimension, long unitQuantity, String unitPrice) {
        return new PricingRate(dimension, unitQuantity, decimal(unitPrice));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
