package com.aicostops.gateway.metering;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Classifies Provider evidence against the exact Pricing Version dimensions
 * frozen on a route attempt. It never derives one financial dimension from
 * another and never turns absent usage into zero.
 */
public final class GatewayUsageClassifier {

    private static final List<String> DIMENSION_ORDER = List.of(
            GatewayUsageDimension.INPUT_TOKEN,
            GatewayUsageDimension.OUTPUT_TOKEN,
            GatewayUsageDimension.CACHED_INPUT_TOKEN);

    public Result classify(Collection<String> requiredPricingDimensions,
            GatewayUsageObservation observation) {
        if (observation == null || requiredPricingDimensions == null
                || requiredPricingDimensions.isEmpty()) {
            return new Result(GatewayUsageStatus.UNKNOWN, List.of());
        }
        var required = Set.copyOf(requiredPricingDimensions);
        if (!GatewayUsageDimension.SUPPORTED_CODES.containsAll(required)) {
            return new Result(GatewayUsageStatus.UNKNOWN, List.of());
        }

        var normalized = new LinkedHashMap<String, GatewayUsageDimension>();
        var malformed = false;
        malformed |= addProviderQuantity(normalized, GatewayUsageDimension.INPUT_TOKEN,
                observation.inputTokens(), observation.providerUsageFinal());
        malformed |= addProviderQuantity(normalized, GatewayUsageDimension.OUTPUT_TOKEN,
                observation.outputTokens(), observation.providerUsageFinal());
        malformed |= addProviderQuantity(normalized, GatewayUsageDimension.CACHED_INPUT_TOKEN,
                observation.cachedInputTokens(), observation.providerUsageFinal());
        if (required.contains(GatewayUsageDimension.REQUEST) && observation.dispatched()) {
            normalized.put(GatewayUsageDimension.REQUEST, new GatewayUsageDimension(
                    GatewayUsageDimension.REQUEST, BigDecimal.ONE,
                    GatewayUsageDimension.GATEWAY_DETERMINISTIC));
        }
        if (malformed) {
            return new Result(GatewayUsageStatus.UNKNOWN, List.of());
        }

        var dimensions = DIMENSION_ORDER.stream()
                .filter(normalized::containsKey)
                .map(normalized::get)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (normalized.containsKey(GatewayUsageDimension.REQUEST)) {
            dimensions.add(normalized.get(GatewayUsageDimension.REQUEST));
        }

        boolean hasCredibleEvidence = !dimensions.isEmpty();
        boolean missingRequired = required.stream().anyMatch(code -> !normalized.containsKey(code));
        boolean partialRequired = required.stream()
                .map(normalized::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(d -> GatewayUsageDimension.PROVIDER_PARTIAL.equals(d.provenance()));
        var status = !hasCredibleEvidence
                ? GatewayUsageStatus.UNKNOWN
                : (missingRequired || partialRequired
                        ? GatewayUsageStatus.INCOMPLETE : GatewayUsageStatus.FINAL);
        return new Result(status, List.copyOf(dimensions));
    }

    private static boolean addProviderQuantity(
            LinkedHashMap<String, GatewayUsageDimension> normalized,
            String code, Integer value, boolean providerFinal) {
        if (value == null) {
            return false;
        }
        if (value < 0) {
            return true;
        }
        normalized.put(code, new GatewayUsageDimension(code, BigDecimal.valueOf(value),
                providerFinal ? GatewayUsageDimension.PROVIDER_FINAL
                        : GatewayUsageDimension.PROVIDER_PARTIAL));
        return false;
    }

    public record Result(GatewayUsageStatus status, List<GatewayUsageDimension> dimensions) {
        public Result {
            dimensions = List.copyOf(dimensions);
        }
    }
}
