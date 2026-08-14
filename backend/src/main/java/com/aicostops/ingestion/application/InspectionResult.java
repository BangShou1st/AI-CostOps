package com.aicostops.ingestion.application;

import java.util.List;

public record InspectionResult(
        String detectedProviderCode,
        String schemaFingerprint,
        boolean compatible,
        List<ImportIssueDraft> issues) {

    public InspectionResult {
        issues = List.copyOf(issues);
    }
}
