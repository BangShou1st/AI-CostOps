package com.aicostops.ingestion.application;

import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-side intermediate normalization result. {@code rawPayload} must already
 * be redacted of secret-like fields before ingestion persists it.
 */
public record NormalizedProviderRecord(
        int index,
        String locator,
        String providerRecordKey,
        Map<String, Object> rawPayload,
        Map<String, Object> normalizedPayload,
        Instant usageStart,
        Instant usageEnd,
        RawRecordNormalizeStatus normalizeStatus,
        List<ImportIssueDraft> issues) {

    public NormalizedProviderRecord {
        // Provider-native blank cells/missing values surface as null and must survive
        // to persistence redaction instead of failing Map.copyOf.
        rawPayload = rawPayload == null || rawPayload.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(rawPayload));
        normalizedPayload = normalizedPayload == null
                ? null : Collections.unmodifiableMap(new LinkedHashMap<>(normalizedPayload));
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
