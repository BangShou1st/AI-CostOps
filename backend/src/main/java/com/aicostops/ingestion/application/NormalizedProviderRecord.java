package com.aicostops.ingestion.application;

import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import java.time.Instant;
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
        rawPayload = rawPayload == null ? Map.of() : Map.copyOf(rawPayload);
        normalizedPayload = normalizedPayload == null ? null : Map.copyOf(normalizedPayload);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
