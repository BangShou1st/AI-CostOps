package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminService {

    private final AuthorizationContextService authorizationContexts;
    private final IamAdminMapper mapper;
    private final AuthorizationInvalidationService invalidation;
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public UserAdminService(
            AuthorizationContextService authorizationContexts,
            IamAdminMapper mapper,
            AuthorizationInvalidationService invalidation,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.invalidation = invalidation;
        this.audit = audit;
        this.clock = clock;
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

    @Transactional
    public UserResponse updateStatus(
            AuthenticatedUser authenticatedUser, long userId, String requestedStatus, String expectedVersion) {
        var context = authorizationContexts.fresh(authenticatedUser);
        authorization.requireOrg(context, "USER_MANAGE");
        requireLockedActor(context);

        var target = mapper.findUserForUpdate(context.organizationId(), userId);
        if (target == null) {
            throw userNotFound();
        }
        var expected = parseVersion(expectedVersion);
        if (expected != target.securityVersion()) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "User status conflict", "The user has changed since it was loaded.");
        }
        if (!"ACTIVE".equals(requestedStatus) && !"DISABLED".equals(requestedStatus)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "User status is invalid", "Status must be ACTIVE or DISABLED.");
        }
        if (requestedStatus.equals(target.status())) {
            return loadCurrentUser(context.organizationId(), userId);
        }

        if (mapper.updateUserStatus(userId, requestedStatus, clock.instant()) != 1) {
            throw new IllegalStateException("User status change must update exactly one user");
        }
        var newVersion = invalidation.bumpInTransaction(userId, target.securityVersion());
        audit.append("DISABLED".equals(requestedStatus) ? "USER_DISABLED" : "USER_ENABLED",
                context.organizationId(), context.userId(), "USER", userId,
                Map.of(
                        "previousStatus", target.status(),
                        "newStatus", requestedStatus,
                        "targetMemberId", Long.toString(target.organizationMemberId())));
        var response = loadCurrentUser(context.organizationId(), userId);
        if (!response.securityVersion().equals(Long.toString(newVersion))) {
            throw new IllegalStateException("Updated user representation has a stale security version");
        }
        return response;
    }

    private List<UserResponse> loadAndAssemble(long organizationId, List<Long> userIds) {
        return assemble(
                mapper.findUsers(organizationId, userIds),
                mapper.findRoleAssignments(organizationId, userIds));
    }

    private UserResponse loadCurrentUser(long organizationId, long userId) {
        var users = loadAndAssemble(organizationId, List.of(userId));
        if (users.isEmpty()) {
            throw userNotFound();
        }
        return users.getFirst();
    }

    private void requireLockedActor(com.aicostops.iam.domain.AuthorizationContext context) {
        if (mapper.lockActiveActor(context.userId(), context.securityVersion(),
                context.organizationMemberId(), context.organizationId()) == null) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                    "Authentication session expired", "Sign in again.");
        }
    }

    private long parseVersion(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "User version is invalid", "Expected version must be a decimal string.");
        }
    }

    private DomainException userNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The user does not exist in the current organization.");
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
                        new UserResponse.RoleReferenceResponse(
                                ApiId.of(assignment.roleId()), assignment.roleCode(), assignment.roleName()),
                        assignment.scopeType(), ApiId.of(assignment.scopeId()), assignment.createdAt()))
                .toList();
        return new UserResponse(ApiId.of(row.id()), row.email(), row.displayName(), row.status(),
                Long.toString(row.securityVersion()), member, assignments);
    }
}
