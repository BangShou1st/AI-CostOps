package com.aicostops.ingestion.application;

import com.aicostops.ingestion.domain.ImportIssueSeverity;

/** Reviewable problem raised during inspection, parsing or normalization. */
public record ImportIssueDraft(
        ImportIssueSeverity severity,
        String issueCode,
        String recordLocator,
        String fieldName,
        String message,
        String rawValueMasked) {
}
