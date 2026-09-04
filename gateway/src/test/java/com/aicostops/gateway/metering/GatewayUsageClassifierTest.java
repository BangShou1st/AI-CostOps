package com.aicostops.gateway.metering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewayUsageClassifierTest {

    private static final Instant DISPATCHED_AT = Instant.parse("2026-09-04T10:00:00Z");

    @Test
    void completeProviderUsageForEveryPricedDimensionIsFinal() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN"),
                GatewayUsageObservation.providerFinal(5, 3, null, DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.FINAL);
        assertThat(result.dimensions()).extracting(GatewayUsageDimension::dimensionCode)
                .containsExactly("INPUT_TOKEN", "OUTPUT_TOKEN");
        assertThat(result.dimensions()).extracting(GatewayUsageDimension::quantity)
                .containsExactly(new java.math.BigDecimal("5"), new java.math.BigDecimal("3"));
    }

    @Test
    void missingOutputIsIncompleteAndTotalIsNotUsedToDeriveIt() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN"),
                GatewayUsageObservation.providerFinal(5, null, null, 8, DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.INCOMPLETE);
        assertThat(result.dimensions()).extracting(GatewayUsageDimension::dimensionCode)
                .containsExactly("INPUT_TOKEN");
        assertThat(result.dimensions()).noneMatch(d -> "OUTPUT_TOKEN".equals(d.dimensionCode()));
    }

    @Test
    void noTrustworthyProviderUsageIsUnknown() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN"),
                GatewayUsageObservation.noUsage(DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.UNKNOWN);
        assertThat(result.dimensions()).isEmpty();
    }

    @Test
    void cachedPricingDimensionCannotBeSilentlyTreatedAsZero() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN", "CACHED_INPUT_TOKEN"),
                GatewayUsageObservation.providerFinal(5, 3, null, DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.INCOMPLETE);
        assertThat(result.dimensions()).noneMatch(d -> "CACHED_INPUT_TOKEN".equals(d.dimensionCode()));
    }

    @Test
    void unsupportedPricingDimensionFailsClosed() {
        var result = classify(Set.of("INPUT_TOKEN", "UNSUPPORTED"),
                GatewayUsageObservation.providerFinal(5, 3, null, DISPATCHED_AT));

        assertThat(result.status()).isNotEqualTo(GatewayUsageStatus.FINAL);
    }

    @Test
    void requestFeeIsDeterministicOnlyAfterProvenDispatch() {
        var result = classify(Set.of("REQUEST"),
                GatewayUsageObservation.noUsage(DISPATCHED_AT).withDispatched(true));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.FINAL);
        assertThat(result.dimensions()).containsExactly(
                new GatewayUsageDimension("REQUEST", new java.math.BigDecimal("1"),
                        GatewayUsageDimension.GATEWAY_DETERMINISTIC));

        var notDispatched = classify(Set.of("REQUEST"), GatewayUsageObservation.noUsage(DISPATCHED_AT));
        assertThat(notDispatched.status()).isEqualTo(GatewayUsageStatus.UNKNOWN);
    }

    @Test
    void negativeProviderQuantityIsMalformedAndNeverFinal() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN"),
                GatewayUsageObservation.providerFinal(-1, 3, null, DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.UNKNOWN);
        assertThat(result.dimensions()).isEmpty();
    }

    @Test
    void explicitlyReportedZeroRemainsTrustworthy() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN"),
                GatewayUsageObservation.providerFinal(5, 0, null, DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.FINAL);
        assertThat(result.dimensions()).anyMatch(d ->
                "OUTPUT_TOKEN".equals(d.dimensionCode())
                        && java.math.BigDecimal.ZERO.compareTo(d.quantity()) == 0);
    }

    @Test
    void partialProviderEvidenceIsNotFinalEvenWhenBothValuesExist() {
        var result = classify(Set.of("INPUT_TOKEN", "OUTPUT_TOKEN"),
                GatewayUsageObservation.providerPartial(5, 3, null, DISPATCHED_AT));

        assertThat(result.status()).isEqualTo(GatewayUsageStatus.INCOMPLETE);
    }

    private static GatewayUsageClassifier.Result classify(
            Set<String> required, GatewayUsageObservation observation) {
        return new GatewayUsageClassifier().classify(required, observation);
    }
}
