package com.aicostops.organization.api;

import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.domain.ProviderAccount;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;
import java.util.Map;

public record ProviderAccountResponse(
        ApiId id,
        String providerCode,
        String displayName,
        String externalAccountRef,
        MasterDataStatus status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public static ProviderAccountResponse from(
            ProviderAccount providerAccount, Map<String, Object> metadata) {
        return new ProviderAccountResponse(
                ApiId.of(providerAccount.id()),
                providerAccount.providerCode(),
                providerAccount.displayName(),
                providerAccount.externalAccountRef(),
                providerAccount.status(),
                metadata,
                providerAccount.createdAt(),
                providerAccount.updatedAt());
    }
}
