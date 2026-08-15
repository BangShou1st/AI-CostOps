package com.aicostops.ingestion.application;

import java.util.List;

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
        List<ImportIssueDraft> issues) {

    public InspectionResult {
        issues = List.copyOf(issues);
    }
}
