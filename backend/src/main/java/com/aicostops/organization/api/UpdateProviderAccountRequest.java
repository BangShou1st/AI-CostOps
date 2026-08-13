package com.aicostops.organization.api;

import com.aicostops.organization.domain.MasterDataStatus;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record UpdateProviderAccountRequest(
        @Size(min = 1, max = 200) String displayName,
        @Size(max = 255) String externalAccountRef,
        MasterDataStatus status,
        Map<String, Object> metadata) {
}
