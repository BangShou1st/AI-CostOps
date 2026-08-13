package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.api.CreateRoleAssignmentRequest;
import com.aicostops.iam.api.UserResponse;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.RoleScopePolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.infrastructure.IamAdminMapper;
import com.aicostops.organization.infrastructure.OrganizationMapper;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleAssignmentService {

    private static final String NATURAL_ASSIGNMENT_CONSTRAINT = "uq_role_assignment_natural";
    private static final Pattern MYSQL_DUPLICATE_KEY_IDENTIFIER = Pattern.compile(
            "\\bfor\\s+key\\s+(['`])([^'`]+)\\1",
            Pattern.CASE_INSENSITIVE);

    private final AuthorizationContextService authorizationContexts;
    private final IamAdminMapper mapper;
    private final OrganizationMapper organizations;
    private final AuthorizationInvalidationService invalidation;
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public RoleAssignmentService(
            AuthorizationContextService authorizationContexts,
            IamAdminMapper mapper,
            OrganizationMapper organizations,
            AuthorizationInvalidationService invalidation,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.organizations = organizations;
        this.invalidation = invalidation;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UserResponse.RoleAssignmentResponse create(
            AuthenticatedUser authenticatedUser, CreateRoleAssignmentRequest request) {
        var context = requireFreshActor(authenticatedUser);
        var targetMemberId = parseId(request.organizationMemberId(), "Organization member ID");
        var target = mapper.findActiveTargetMemberForUpdate(targetMemberId, context.organizationId());
        if (target == null) {
            throw resourceNotFound();
        }

        var roleId = parseId(request.roleId(), "Role ID");
        var role = mapper.findRole(roleId);
        if (role == null) {
            throw validationFailed("The Role does not exist.");
        }
        var scopeType = parseScopeType(request.scopeType());
        var scopeId = parseId(request.scopeId(), "Scope ID");
        requireVisibleScope(context, scopeType, scopeId);
        RoleScopePolicy.requireValid(role.code(), scopeType);

        if (mapper.findRoleAssignmentId(targetMemberId, roleId, scopeType.name(), scopeId) != null) {
            throw duplicateAssignment();
        }
        var createdAt = clock.instant();
        try {
            if (mapper.insertRoleAssignment(targetMemberId, roleId, scopeType.name(), scopeId,
                    context.organizationMemberId(), createdAt) != 1) {
                throw new IllegalStateException("Role assignment must insert exactly one row");
            }
        } catch (DuplicateKeyException exception) {
            if (!causedByNaturalAssignmentConstraint(exception)) {
                throw exception;
            }
            throw duplicateAssignment();
        }
        var assignmentId = mapper.lastInsertId();
        audit.append("ROLE_ASSIGNED", context.organizationId(), context.userId(),
                "ROLE_ASSIGNMENT", assignmentId, roleMetadata(targetMemberId, role.code(), scopeType, scopeId));
        invalidation.bumpInTransaction(target.userId(), target.securityVersion());
        return new UserResponse.RoleAssignmentResponse(
                ApiId.of(assignmentId),
                new UserResponse.RoleReferenceResponse(ApiId.of(role.id()), role.code(), role.name()),
                scopeType.name(), ApiId.of(scopeId), createdAt);
    }

    @Transactional
    public void revoke(AuthenticatedUser authenticatedUser, long assignmentId) {
        var context = requireFreshActor(authenticatedUser);
        var assignment = mapper.findRoleAssignmentForUpdate(assignmentId, context.organizationId());
        if (assignment == null) {
            throw resourceNotFound();
        }
        if (mapper.deleteRoleAssignment(assignmentId) != 1) {
            throw resourceNotFound();
        }
        audit.append("ROLE_REVOKED", context.organizationId(), context.userId(),
                "ROLE_ASSIGNMENT", assignmentId,
                roleMetadata(assignment.organizationMemberId(), assignment.roleCode(),
                        ScopeType.valueOf(assignment.scopeType()), assignment.scopeId()));
        invalidation.bumpInTransaction(assignment.userId(), assignment.securityVersion());
    }

    private AuthorizationContext requireFreshActor(AuthenticatedUser authenticatedUser) {
        var context = authorizationContexts.fresh(authenticatedUser);
        authorization.requireOrg(context, "ROLE_ASSIGN");
        if (mapper.lockActiveActor(context.userId(), context.securityVersion(),
                context.organizationMemberId(), context.organizationId()) == null) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                    "Authentication session expired", "Sign in again.");
        }
        return context;
    }

    private void requireVisibleScope(AuthorizationContext context, ScopeType scopeType, long scopeId) {
        if (scopeType == ScopeType.ORG) {
            if (scopeId != context.organizationId()) {
                throw validationFailed("Organization scope must use the current organization ID.");
            }
            return;
        }
        if (!organizations.scopeResourceExists(scopeType, scopeId, context.organizationId())) {
            throw resourceNotFound();
        }
    }

    private ScopeType parseScopeType(String value) {
        try {
            return ScopeType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw validationFailed("Scope type must be ORG, PROJECT, TEAM, or COST_CENTER.");
        }
    }

    private long parseId(String value, String field) {
        try {
            var id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw validationFailed(field + " must be a positive decimal string.");
        }
    }

    private Map<String, String> roleMetadata(
            long targetMemberId, String roleCode, ScopeType scopeType, long scopeId) {
        return Map.of(
                "targetMemberId", Long.toString(targetMemberId),
                "roleCode", roleCode,
                "scopeType", scopeType.name(),
                "scopeId", Long.toString(scopeId));
    }

    private boolean causedByNaturalAssignmentConstraint(DuplicateKeyException exception) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(exception);
        while (!pending.isEmpty()) {
            var cause = pending.removeFirst();
            if (!visited.add(cause)) {
                continue;
            }
            if (cause instanceof SQLException sqlException) {
                if (namesNaturalAssignmentConstraint(sqlException.getMessage())) {
                    return true;
                }
                if (sqlException.getNextException() != null) {
                    pending.addLast(sqlException.getNextException());
                }
            }
            if (cause.getCause() != null) {
                pending.addLast(cause.getCause());
            }
        }
        return false;
    }

    private boolean namesNaturalAssignmentConstraint(String message) {
        if (message == null) {
            return false;
        }
        var matcher = MYSQL_DUPLICATE_KEY_IDENTIFIER.matcher(message);
        if (!matcher.find()) {
            return false;
        }
        var qualifiedIdentifier = matcher.group(2);
        if (matcher.find()) {
            return false;
        }
        var qualifierSeparator = qualifiedIdentifier.lastIndexOf('.');
        var constraint = qualifiedIdentifier.substring(qualifierSeparator + 1);
        return NATURAL_ASSIGNMENT_CONSTRAINT.equalsIgnoreCase(constraint);
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Role assignment validation failed", detail);
    }

    private DomainException duplicateAssignment() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Role assignment conflict", "The Role is already assigned at the requested scope.");
    }

    private DomainException resourceNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The requested resource is not available in the current organization.");
    }
}
