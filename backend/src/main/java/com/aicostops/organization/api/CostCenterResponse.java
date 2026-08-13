package com.aicostops.organization.api;

import com.aicostops.organization.domain.CostCenter;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;

public record CostCenterResponse(
        ApiId id,
        String code,
        String name,
        MasterDataStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static CostCenterResponse from(CostCenter costCenter) {
        return new CostCenterResponse(ApiId.of(costCenter.id()), costCenter.code(), costCenter.name(),
                costCenter.status(), costCenter.createdAt(), costCenter.updatedAt());
    }
}
