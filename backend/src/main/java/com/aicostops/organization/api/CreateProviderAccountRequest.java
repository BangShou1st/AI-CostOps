package com.aicostops.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateProviderAccountRequest(
        @NotBlank @Size(max = 100) String providerCode,
        @NotBlank @Size(max = 200) String displayName,
        @Size(max = 255) String externalAccountRef,
        Map<String, Object> metadata) {
}
