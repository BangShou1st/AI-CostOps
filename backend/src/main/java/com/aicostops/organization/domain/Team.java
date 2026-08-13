package com.aicostops.organization.domain;

import java.time.Instant;

public record Team(
        long id,
        long orgId,
        String code,
        String name,
        MasterDataStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
