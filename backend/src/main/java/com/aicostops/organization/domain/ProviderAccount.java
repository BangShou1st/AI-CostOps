package com.aicostops.organization.domain;

import java.time.Instant;

public record ProviderAccount(
        long id,
        long orgId,
        String providerCode,
        String displayName,
        String externalAccountRef,
        MasterDataStatus status,
        String metadataJson,
        Instant createdAt,
        Instant updatedAt) {
}
