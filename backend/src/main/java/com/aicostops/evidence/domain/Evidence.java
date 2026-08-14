package com.aicostops.evidence.domain;

import java.time.Instant;

/**
 * Immutable byte identity of one provider evidence file within one organization.
 *
 * <p>{@code sha256}, {@code objectKey} and {@code sizeBytes} are fixed once the object
 * is accepted; the storage lifecycle is carried by {@code storageStatus}.
 */
public record Evidence(
        long id,
        long organizationId,
        String sha256,
        String objectKey,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        long uploadedByMemberId,
        EvidenceStorageStatus storageStatus,
        String storageErrorCode,
        Instant createdAt,
        Instant updatedAt) {
}
