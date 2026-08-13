package com.aicostops.iam.domain;

import java.util.Objects;
import java.util.Set;

public record AuthorizationContext(
        long userId,
        long organizationId,
        long organizationMemberId,
        long securityVersion,
        Set<ScopedPermissionGrant> grants,
        Set<String> roleCodes) {

    public AuthorizationContext {
        grants = Set.copyOf(Objects.requireNonNull(grants, "Permission grants are required"));
        roleCodes = Set.copyOf(Objects.requireNonNull(roleCodes, "Role codes are required"));
    }
}
