package com.aicostops.ingestion.domain;

import java.time.Instant;

/** Reviewable data problem attached to one Attempt (and optionally one raw record). */
public record ImportIssue(
        long id,
        long importAttemptId,
        Long rawProviderRecordId,
        ImportIssueSeverity severity,
        String issueCode,
        String recordLocator,
        String fieldName,
        String message,
        String rawValueMasked,
        Instant createdAt) {
}
