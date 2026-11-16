package com.aicostops.ingestion.application;

import java.util.List;
import java.util.Map;

/**
 * Outcome of {@link ProviderAdapter#inspect}.
 *
 * <p>{@code schemaVariant} identifies which supported logical contract the evidence
 * matches, while {@code schemaFingerprint} describes the canonical schema descriptor
 * actually observed. The variant is stable across record values and is not derived
 * from row data.
 */
public record InspectionResult(
        String detectedProviderCode,
        String schemaVariant,
        String schemaFingerprint,
        boolean compatible,
        List<ImportIssueDraft> issues,
        Map<String, Object> adapterState) {

    public InspectionResult {
        issues = List.copyOf(issues);
        adapterState = adapterState == null ? Map.of() : Map.copyOf(adapterState);
    }

    public InspectionResult(
            String detectedProviderCode,
            String schemaVariant,
            String schemaFingerprint,
            boolean compatible,
            List<ImportIssueDraft> issues) {
        this(detectedProviderCode, schemaVariant, schemaFingerprint, compatible, issues, Map.of());
    }
}
