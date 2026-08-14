package com.aicostops.iam.domain;

import java.util.Objects;

public record ScopedPermissionGrant(String permissionCode, ScopeType scopeType, long scopeId) {

    public ScopedPermissionGrant {
        permissionCode = Objects.requireNonNull(permissionCode, "Permission code is required");
        scopeType = Objects.requireNonNull(scopeType, "Scope type is required");
    }
}
