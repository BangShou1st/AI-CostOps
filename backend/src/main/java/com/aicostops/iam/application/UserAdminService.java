package com.aicostops.iam.application;

import com.aicostops.iam.api.RoleResponse;
import com.aicostops.iam.api.UserResponse;
import com.aicostops.iam.infrastructure.IamAdminMapper;
import com.aicostops.iam.infrastructure.IamAdminMapper.RoleAssignmentRow;
import com.aicostops.iam.infrastructure.IamAdminMapper.UserRow;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UserAdminService {

    private final AuthorizationContextService authorizationContexts;
    private final IamAdminMapper mapper;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public UserAdminService(AuthorizationContextService authorizationContexts, IamAdminMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public PageResponse<UserResponse> list(AuthenticatedUser authenticatedUser, PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "USER_READ");
        var total = mapper.countUsers(context.organizationId());
        var ids = mapper.findUserPageIds(
                context.organizationId(), Math.multiplyExact((long) page.page(), page.size()), page.size());
        if (ids.isEmpty()) {
            return PageResponse.of(List.of(), page, total);
        }
        return PageResponse.of(loadAndAssemble(context.organizationId(), ids), page, total);
    }

    public UserResponse get(AuthenticatedUser authenticatedUser, long userId) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "USER_READ");
        var users = loadAndAssemble(context.organizationId(), List.of(userId));
        if (users.isEmpty()) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Resource not found", "The user does not exist in the current organization.");
        }
        return users.getFirst();
    }

    private List<UserResponse> loadAndAssemble(long organizationId, List<Long> userIds) {
        return assemble(
                mapper.findUsers(organizationId, userIds),
                mapper.findRoleAssignments(organizationId, userIds));
    }

    private List<UserResponse> assemble(List<UserRow> rows, List<RoleAssignmentRow> assignments) {
        var assignmentsByUser = new LinkedHashMap<Long, List<RoleAssignmentRow>>();
        for (var assignment : assignments) {
            assignmentsByUser.computeIfAbsent(assignment.userId(), ignored -> new ArrayList<>()).add(assignment);
        }
        return rows.stream()
                .map(row -> toResponse(row, assignmentsByUser))
                .toList();
    }

    private UserResponse toResponse(UserRow row, Map<Long, List<RoleAssignmentRow>> assignmentsByUser) {
        var member = new UserResponse.OrganizationMemberResponse(
                ApiId.of(row.organizationMemberId()), row.organizationMemberStatus(), row.employeeNo(),
                row.defaultCostCenterId() == null ? null : ApiId.of(row.defaultCostCenterId()));
        var assignments = assignmentsByUser.getOrDefault(row.id(), List.of()).stream()
                .map(assignment -> new UserResponse.RoleAssignmentResponse(
                        ApiId.of(assignment.id()),
                        new RoleResponse(ApiId.of(assignment.roleId()), assignment.roleCode(), assignment.roleName()),
                        assignment.scopeType(), ApiId.of(assignment.scopeId()), assignment.createdAt()))
                .toList();
        return new UserResponse(ApiId.of(row.id()), row.email(), row.displayName(), row.status(),
                Long.toString(row.securityVersion()), member, assignments);
    }
}
