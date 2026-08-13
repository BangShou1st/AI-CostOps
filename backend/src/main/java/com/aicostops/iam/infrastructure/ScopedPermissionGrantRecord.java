package com.aicostops.iam.infrastructure;

import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;

public record ScopedPermissionGrantRecord(String roleCode, String permissionCode, String scopeType, long scopeId) {

    public ScopedPermissionGrant toDomain() {
        return new ScopedPermissionGrant(permissionCode, ScopeType.valueOf(scopeType), scopeId);
    }
}
