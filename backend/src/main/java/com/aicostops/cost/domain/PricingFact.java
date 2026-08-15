package com.aicostops.cost.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One row of {@code pricing_fact}: a provider-reported unit price. */
public record PricingFact(
        long orgId,
        long rawRecordId,
        int factIndex,
        String providerCode,
        String serviceCode,
        String model,
        String meterCode,
        BigDecimal unitPrice,
        String currency,
        String pricingUnit,
        Instant periodStart,
        Instant periodEnd,
        Map<String, Object> metadata) {

    public PricingFact {
        metadata = metadata == null ? null : Map.copyOf(metadata);
    }
}
