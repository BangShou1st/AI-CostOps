package com.aicostops.cost.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One row of {@code charge_fact}: the authoritative provider-reported charge. */
public record ChargeFact(
        long orgId,
        long rawRecordId,
        int factIndex,
        String providerCode,
        ChargeCategory chargeCategory,
        BigDecimal amount,
        String currency,
        String fundingSource,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal outstandingAmount,
        Instant periodStart,
        Instant periodEnd,
        ReviewStatus reviewStatus,
        Long duplicateOfChargeId,
        Map<String, Object> metadata) {

    public ChargeFact {
        metadata = metadata == null ? null : Map.copyOf(metadata);
    }
}
