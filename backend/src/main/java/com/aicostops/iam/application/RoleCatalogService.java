package com.aicostops.iam.application;

import com.aicostops.iam.api.PermissionResponse;
import com.aicostops.iam.api.RoleResponse;
import com.aicostops.iam.infrastructure.IamAdminMapper;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RoleCatalogService {

    private final AuthorizationContextService authorizationContexts;
    private final IamAdminMapper mapper;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public RoleCatalogService(AuthorizationContextService authorizationContexts, IamAdminMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public List<RoleResponse> roles(AuthenticatedUser authenticatedUser) {
        requireRoleRead(authenticatedUser);
        var permissionsByRole = new LinkedHashMap<Long, List<PermissionResponse>>();
        for (var permission : mapper.findRolePermissions()) {
            permissionsByRole.computeIfAbsent(permission.roleId(), ignored -> new ArrayList<>())
                    .add(new PermissionResponse(
                            ApiId.of(permission.permissionId()), permission.permissionCode(), permission.permissionName()));
        }
        return mapper.findRoles().stream()
                .map(role -> new RoleResponse(ApiId.of(role.id()), role.code(), role.name(),
                        permissionsByRole.getOrDefault(role.id(), List.of())))
                .toList();
    }

    public List<PermissionResponse> permissions(AuthenticatedUser authenticatedUser) {
        requireRoleRead(authenticatedUser);
        return mapper.findPermissions().stream()
                .map(permission -> new PermissionResponse(
                        ApiId.of(permission.id()), permission.code(), permission.name()))
                .toList();
    }

    private void requireRoleRead(AuthenticatedUser authenticatedUser) {
        authorization.requireOrg(authorizationContexts.current(authenticatedUser), "ROLE_READ");
    }
}
