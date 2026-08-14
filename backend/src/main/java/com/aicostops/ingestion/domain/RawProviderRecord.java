package com.aicostops.ingestion.domain;

import java.time.Instant;

/**
 * One provider-native row retained as ingestion lineage. Raw payloads stored here
 * are already redacted of secret-like fields; the Evidence object stays the
 * authoritative original byte source.
 */
public record RawProviderRecord(
        long id,
        long importAttemptId,
        long recordIndex,
        String recordLocator,
        String providerRecordKey,
        String rawPayload,
        String normalizedPayload,
        Instant usageStart,
        Instant usageEnd,
        RawRecordNormalizeStatus normalizeStatus,
        Instant createdAt) {
}
