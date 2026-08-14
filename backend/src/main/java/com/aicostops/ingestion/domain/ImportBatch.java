package com.aicostops.ingestion.domain;

import java.time.Instant;

/**
 * One stable interpretation context for one Evidence object.
 * Identity: evidence + provider account + source type + parser version.
 */
public record ImportBatch(
        long id,
        long organizationId,
        long evidenceId,
        long providerAccountId,
        String expectedProviderCode,
        ImportSourceType sourceType,
        String parserVersion,
        ImportBatchStatus status,
        Instant periodStart,
        Instant periodEnd,
        long createdByMemberId,
        Instant createdAt,
        Instant updatedAt) {
}
