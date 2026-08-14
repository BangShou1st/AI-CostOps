package com.aicostops.iam.application;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

public final class M1AuthorizationService {

    public void requireOrg(AuthorizationContext context, String permissionCode) {
        var grants = applicableGrants(context, permissionCode);
        requireAnyApplicableGrant(grants);
        if (grants.stream().noneMatch(grant -> isCurrentOrganizationGrant(context, grant))) {
            throw resourceNotFound();
        }
    }

    public ResourceScope requireList(AuthorizationContext context, String permissionCode, ScopeType resourceType) {
        var grants = applicableGrants(context, permissionCode);
        requireAnyApplicableGrant(grants);
        if (grants.stream().anyMatch(grant -> isCurrentOrganizationGrant(context, grant))) {
            return new ResourceScope(true, Set.of());
        }

        var resourceIds = grants.stream()
                .filter(grant -> grant.scopeType() == resourceType)
                .map(ScopedPermissionGrant::scopeId)
                .collect(Collectors.toUnmodifiableSet());
        return new ResourceScope(false, resourceIds);
    }

    public void requireResource(AuthorizationContext context, String permissionCode,
            ScopeType resourceType, long resourceId) {
        var grants = applicableGrants(context, permissionCode);
        requireAnyApplicableGrant(grants);
        if (grants.stream().noneMatch(grant -> matchesResource(context, grant, resourceType, resourceId))) {
            throw resourceNotFound();
        }
    }

    private static Set<ScopedPermissionGrant> applicableGrants(AuthorizationContext context, String permissionCode) {
        var applicableScopes = M1AdminPermissionPolicy.applicableScopes(permissionCode);
        return context.grants().stream()
                .filter(grant -> grant.permissionCode().equals(permissionCode))
                .filter(grant -> applicableScopes.contains(grant.scopeType()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireAnyApplicableGrant(Set<ScopedPermissionGrant> grants) {
        if (grants.isEmpty()) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Permission is required", "The required permission is not granted at an applicable scope.");
        }
    }

    private static boolean matchesResource(AuthorizationContext context, ScopedPermissionGrant grant,
            ScopeType resourceType, long resourceId) {
        var organizationGrantMatches = isCurrentOrganizationGrant(context, grant)
                && (resourceType != ScopeType.ORG || resourceId == context.organizationId());
        var typedGrantMatches = grant.scopeType() == resourceType && grant.scopeId() == resourceId;
        return organizationGrantMatches || typedGrantMatches;
    }

    private static boolean isCurrentOrganizationGrant(AuthorizationContext context, ScopedPermissionGrant grant) {
        return grant.scopeType() == ScopeType.ORG && grant.scopeId() == context.organizationId();
    }

    private static DomainException resourceNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The resource is not available at the granted scope.");
    }
}
