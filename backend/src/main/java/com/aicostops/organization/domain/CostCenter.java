package com.aicostops.organization.domain;

import java.time.Instant;

public record CostCenter(
        long id,
        long orgId,
        String code,
        String name,
        MasterDataStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
