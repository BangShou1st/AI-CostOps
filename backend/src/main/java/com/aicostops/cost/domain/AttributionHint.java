package com.aicostops.cost.domain;

import java.math.BigDecimal;
import java.util.Map;

/** One row of {@code attribution_hint}: a provider-native attribution signal. */
public record AttributionHint(
        long orgId,
        long rawRecordId,
        int factIndex,
        HintType hintType,
        CandidateScopeType candidateScopeType,
        Long candidateScopeId,
        String providerValue,
        BigDecimal confidence,
        Map<String, Object> metadata) {

    public AttributionHint {
        metadata = metadata == null ? null : Map.copyOf(metadata);
    }
}
