package com.aicostops.organization.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.AuthorizationInvalidationService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.organization.api.AddProjectMemberRequest;
import com.aicostops.organization.api.ProjectMemberResponse;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.infrastructure.ProjectMemberMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectMembershipService {

    private final AuthorizationContextService authorizationContexts;
    private final ProjectMemberMapper mapper;
    private final AuthorizationInvalidationService invalidation;
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ProjectMembershipService(
            AuthorizationContextService authorizationContexts,
            ProjectMemberMapper mapper,
            AuthorizationInvalidationService invalidation,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.invalidation = invalidation;
        this.audit = audit;
        this.clock = clock;
    }

    public PageResponse<ProjectMemberResponse> list(
            AuthenticatedUser authenticatedUser,
            long projectId,
            MasterDataStatus status,
            PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        requireRead(context, projectId);
        if (mapper.findCurrentOrganizationProject(projectId, context.organizationId()) == null) {
            throw notFound();
        }
        var statusValue = status == null ? null : status.name();
        var total = mapper.countPage(projectId, context.organizationId(), statusValue);
        var rows = mapper.findPage(projectId, context.organizationId(), statusValue,
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(rows.stream().map(ProjectMemberResponse::from).toList(), page, total);
    }

    @Transactional
    public ProjectMemberResponse add(
            AuthenticatedUser authenticatedUser,
            long projectId,
            AddProjectMemberRequest request) {
        var context = authorizationContexts.fresh(authenticatedUser);
        authorization.requireResource(context, "PROJECT_MEMBER_MANAGE", ScopeType.PROJECT, projectId);
        lockActiveProject(projectId, context.organizationId());
        var organizationMemberId = parseId(request.organizationMemberId());
        var target = mapper.lockActiveTarget(organizationMemberId, context.organizationId());
        if (target == null) {
            throw notFound();
        }
        var existing = mapper.lockNaturalMembership(projectId, organizationMemberId, context.organizationId());
        String previousStatus;
        long projectMemberId;
        var now = clock.instant();
        if (existing == null) {
            previousStatus = null;
            try {
                if (mapper.insert(projectId, organizationMemberId, now) != 1) {
                    throw new IllegalStateException("Project membership must insert exactly one row");
                }
            } catch (DuplicateKeyException exception) {
                throw conflict("The organization member already has a project membership.");
            }
            projectMemberId = mapper.lastInsertId();
        } else {
            previousStatus = existing.status().name();
            if (existing.status() != MasterDataStatus.DISABLED) {
                throw conflict(existing.status() == MasterDataStatus.ACTIVE
                        ? "The organization member is already active in the project."
                        : "An archived project membership cannot be reactivated.");
            }
            if (mapper.transition(existing.id(), projectId, MasterDataStatus.ACTIVE.name(), now) != 1) {
                throw notFound();
            }
            projectMemberId = existing.id();
        }
        changed(context, projectId, projectMemberId, previousStatus, MasterDataStatus.ACTIVE.name(), target);
        var created = mapper.findById(projectMemberId, projectId, context.organizationId());
        if (created == null) {
            throw new IllegalStateException("Changed project membership must be readable");
        }
        return ProjectMemberResponse.from(created);
    }

    @Transactional
    public void remove(AuthenticatedUser authenticatedUser, long projectId, long projectMemberId) {
        var context = authorizationContexts.fresh(authenticatedUser);
        authorization.requireResource(context, "PROJECT_MEMBER_MANAGE", ScopeType.PROJECT, projectId);
        lockActiveProject(projectId, context.organizationId());
        var organizationMemberId = mapper.findMembershipTarget(
                projectMemberId, projectId, context.organizationId());
        if (organizationMemberId == null) {
            throw notFound();
        }
        var target = mapper.lockActiveTarget(organizationMemberId, context.organizationId());
        if (target == null) {
            throw notFound();
        }
        var membership = mapper.lockMembershipById(projectMemberId, projectId, context.organizationId());
        if (membership == null) {
            throw notFound();
        }
        if (membership.status() != MasterDataStatus.ACTIVE) {
            throw conflict("The project membership is not active.");
        }
        if (mapper.transition(projectMemberId, projectId, MasterDataStatus.DISABLED.name(), membership.joinedAt()) != 1) {
            throw notFound();
        }
        changed(context, projectId, projectMemberId, MasterDataStatus.ACTIVE.name(),
                MasterDataStatus.DISABLED.name(), target);
    }

    private void requireRead(AuthorizationContext context, long projectId) {
        var permissions = java.util.Set.of("PROJECT_READ", "PROJECT_MEMBER_MANAGE");
        var grants = context.grants().stream()
                .filter(grant -> permissions.contains(grant.permissionCode()))
                .filter(grant -> M1AdminPermissionPolicy.applicableScopes(grant.permissionCode())
                        .contains(grant.scopeType()))
                .toList();
        if (grants.isEmpty()) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Permission is required", "Project read permission is required at an applicable scope.");
        }
        var matches = grants.stream().anyMatch(grant ->
                (grant.scopeType() == ScopeType.ORG && grant.scopeId() == context.organizationId())
                        || (grant.scopeType() == ScopeType.PROJECT && grant.scopeId() == projectId));
        if (!matches) {
            throw notFound();
        }
    }

    private void lockActiveProject(long projectId, long organizationId) {
        if (mapper.lockActiveProject(projectId, organizationId) == null) {
            throw notFound();
        }
    }

    private void changed(
            AuthorizationContext context,
            long projectId,
            long projectMemberId,
            String previousStatus,
            String newStatus,
            ProjectMemberMapper.TargetMember target) {
        audit.append("MEMBERSHIP_CHANGED", context.organizationId(), context.userId(),
                "PROJECT_MEMBER", projectMemberId,
                membershipMetadata(projectId, projectMemberId, previousStatus, newStatus));
        invalidation.bumpInTransaction(target.userId(), target.securityVersion());
    }

    private Map<String, Object> membershipMetadata(
            long projectId, long projectMemberId, String previousStatus, String newStatus) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("parentType", "PROJECT");
        metadata.put("parentId", Long.toString(projectId));
        metadata.put("memberId", Long.toString(projectMemberId));
        metadata.put("previousStatus", previousStatus);
        metadata.put("newStatus", newStatus);
        return metadata;
    }

    private long parseId(String value) {
        try {
            var id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Project membership validation failed",
                    "Organization member ID must be a positive decimal string.");
        }
    }

    private DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Project membership conflict", detail);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The project membership resource is not available in the current organization.");
    }
}
