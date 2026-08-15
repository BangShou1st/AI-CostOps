package com.aicostops.cost.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of {@code consumption_fact}: a provider-reported meter reading. */
public record ConsumptionFact(
        long orgId,
        long rawRecordId,
        int factIndex,
        String providerCode,
        String serviceCode,
        String model,
        String meterCode,
        BigDecimal quantity,
        String unit,
        Instant usageStart,
        Instant usageEnd,
        String timeGrain,
        String providerOrgRef,
        String providerProjectRef,
        String providerUserRef,
        String providerApiKeyHash,
        String providerApiKeyLabel) {
}
