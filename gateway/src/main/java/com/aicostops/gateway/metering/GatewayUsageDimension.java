package com.aicostops.gateway.metering;

import java.math.BigDecimal;
import java.util.Set;

/** Exact, normalized billable quantity. */
public record GatewayUsageDimension(
        String dimensionCode,
        BigDecimal quantity,
        String provenance) {

    public static final String INPUT_TOKEN = "INPUT_TOKEN";
    public static final String OUTPUT_TOKEN = "OUTPUT_TOKEN";
    public static final String CACHED_INPUT_TOKEN = "CACHED_INPUT_TOKEN";
    public static final String REQUEST = "REQUEST";
    public static final String PROVIDER_FINAL = "PROVIDER_FINAL";
    public static final String PROVIDER_PARTIAL = "PROVIDER_PARTIAL";
    public static final String GATEWAY_DETERMINISTIC = "GATEWAY_DETERMINISTIC";

    public static final Set<String> SUPPORTED_CODES = Set.of(
            INPUT_TOKEN, OUTPUT_TOKEN, CACHED_INPUT_TOKEN, REQUEST);

    public GatewayUsageDimension {
        if (!SUPPORTED_CODES.contains(dimensionCode)) {
            throw new IllegalArgumentException("Unsupported usage dimension: " + dimensionCode);
        }
        if (quantity == null || quantity.signum() < 0) {
            throw new IllegalArgumentException("Usage quantity must be non-negative");
        }
        if (!Set.of(PROVIDER_FINAL, PROVIDER_PARTIAL, GATEWAY_DETERMINISTIC)
                .contains(provenance)) {
            throw new IllegalArgumentException("Unsupported usage provenance: " + provenance);
        }
    }
}
