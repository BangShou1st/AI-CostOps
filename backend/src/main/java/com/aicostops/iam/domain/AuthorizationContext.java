package com.aicostops.iam.domain;

import java.util.Objects;
import java.util.Set;

public record AuthorizationContext(
        long userId,
        long organizationId,
        long organizationMemberId,
        long securityVersion,
        Set<String> roleCodes,
        Set<ScopedPermissionGrant> grants) {

    public AuthorizationContext {
        roleCodes = Set.copyOf(Objects.requireNonNull(roleCodes, "Role codes are required"));
        grants = Set.copyOf(Objects.requireNonNull(grants, "Permission grants are required"));
    }
}
