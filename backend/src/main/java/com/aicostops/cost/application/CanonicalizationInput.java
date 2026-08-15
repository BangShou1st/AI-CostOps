package com.aicostops.cost.application;

import java.time.Instant;

/**
 * Canonicalization context for exactly one raw record, assembled by the
 * ingestion persist loop. {@code orgId}/{@code providerCode} are the frozen
 * snapshot of the owning {@code ImportBatch}; {@code providerCode} is never
 * taken from the payload itself.
 */
public record CanonicalizationInput(
        long orgId,
        String providerCode,
        long importAttemptId,
        long rawRecordId,
        long recordIndex,
        String recordLocator,
        String normalizedPayload,
        Instant usageStart,
        Instant usageEnd) {
}
