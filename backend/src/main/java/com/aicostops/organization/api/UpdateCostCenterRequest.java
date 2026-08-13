package com.aicostops.organization.api;

import com.aicostops.organization.domain.MasterDataStatus;
import jakarta.validation.constraints.Size;

public record UpdateCostCenterRequest(
        @Size(min = 1, max = 200) String name,
        MasterDataStatus status) {
}
