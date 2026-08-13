package com.aicostops.organization.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.AuthorizationInvalidationService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.organization.api.AddTeamMemberRequest;
import com.aicostops.organization.api.TeamMemberResponse;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.infrastructure.TeamMemberMapper;
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
public class TeamMembershipService {

    private final AuthorizationContextService authorizationContexts;
    private final TeamMemberMapper mapper;
    private final AuthorizationInvalidationService invalidation;
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public TeamMembershipService(
            AuthorizationContextService authorizationContexts,
            TeamMemberMapper mapper,
            AuthorizationInvalidationService invalidation,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.invalidation = invalidation;
        this.audit = audit;
        this.clock = clock;
    }

    public PageResponse<TeamMemberResponse> list(
            AuthenticatedUser authenticatedUser,
            long teamId,
            MasterDataStatus status,
            PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        requireRead(context, teamId);
        if (mapper.findCurrentOrganizationTeam(teamId, context.organizationId()) == null) {
            throw notFound();
        }
        var statusValue = status == null ? null : status.name();
        var total = mapper.countPage(teamId, context.organizationId(), statusValue);
        var rows = mapper.findPage(teamId, context.organizationId(), statusValue,
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(rows.stream().map(TeamMemberResponse::from).toList(), page, total);
    }

    @Transactional
    public TeamMemberResponse add(
            AuthenticatedUser authenticatedUser,
            long teamId,
            AddTeamMemberRequest request) {
        var context = authorizationContexts.fresh(authenticatedUser);
        authorization.requireResource(context, "TEAM_MANAGE", ScopeType.TEAM, teamId);
        lockActiveTeam(teamId, context.organizationId());
        var organizationMemberId = parseId(request.organizationMemberId());
        var target = mapper.lockActiveTarget(organizationMemberId, context.organizationId());
        if (target == null) {
            throw notFound();
        }
        var existing = mapper.lockNaturalMembership(teamId, organizationMemberId, context.organizationId());
        String previousStatus;
        long teamMemberId;
        var now = clock.instant();
        if (existing == null) {
            previousStatus = null;
            try {
                if (mapper.insert(teamId, organizationMemberId, now) != 1) {
                    throw new IllegalStateException("Team membership must insert exactly one row");
                }
            } catch (DuplicateKeyException exception) {
                throw conflict("The organization member already has a team membership.");
            }
            teamMemberId = mapper.lastInsertId();
        } else {
            previousStatus = existing.status().name();
            if (existing.status() != MasterDataStatus.DISABLED) {
                throw conflict(existing.status() == MasterDataStatus.ACTIVE
                        ? "The organization member is already active in the team."
                        : "An archived team membership cannot be reactivated.");
            }
            if (mapper.transition(existing.id(), teamId, MasterDataStatus.ACTIVE.name(), now) != 1) {
                throw notFound();
            }
            teamMemberId = existing.id();
        }
        changed(context, teamId, teamMemberId, previousStatus, MasterDataStatus.ACTIVE.name(), target);
        var created = mapper.findById(teamMemberId, teamId, context.organizationId());
        if (created == null) {
            throw new IllegalStateException("Changed team membership must be readable");
        }
        return TeamMemberResponse.from(created);
    }

    @Transactional
    public void remove(AuthenticatedUser authenticatedUser, long teamId, long teamMemberId) {
        var context = authorizationContexts.fresh(authenticatedUser);
        authorization.requireResource(context, "TEAM_MANAGE", ScopeType.TEAM, teamId);
        lockActiveTeam(teamId, context.organizationId());
        var organizationMemberId = mapper.findMembershipTarget(
                teamMemberId, teamId, context.organizationId());
        if (organizationMemberId == null) {
            throw notFound();
        }
        var target = mapper.lockActiveTarget(organizationMemberId, context.organizationId());
        if (target == null) {
            throw notFound();
        }
        var membership = mapper.lockMembershipById(teamMemberId, teamId, context.organizationId());
        if (membership == null) {
            throw notFound();
        }
        if (membership.status() != MasterDataStatus.ACTIVE) {
            throw conflict("The team membership is not active.");
        }
        if (mapper.transition(teamMemberId, teamId, MasterDataStatus.DISABLED.name(), membership.joinedAt()) != 1) {
            throw notFound();
        }
        changed(context, teamId, teamMemberId, MasterDataStatus.ACTIVE.name(),
                MasterDataStatus.DISABLED.name(), target);
    }

    private void requireRead(AuthorizationContext context, long teamId) {
        var permissions = java.util.Set.of("TEAM_READ", "TEAM_MANAGE");
        var grants = context.grants().stream()
                .filter(grant -> permissions.contains(grant.permissionCode()))
                .filter(grant -> M1AdminPermissionPolicy.applicableScopes(grant.permissionCode())
                        .contains(grant.scopeType()))
                .toList();
        if (grants.isEmpty()) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Permission is required", "Team read permission is required at an applicable scope.");
        }
        var matches = grants.stream().anyMatch(grant ->
                (grant.scopeType() == ScopeType.ORG && grant.scopeId() == context.organizationId())
                        || (grant.scopeType() == ScopeType.TEAM && grant.scopeId() == teamId));
        if (!matches) {
            throw notFound();
        }
    }

    private void lockActiveTeam(long teamId, long organizationId) {
        if (mapper.lockActiveTeam(teamId, organizationId) == null) {
            throw notFound();
        }
    }

    private void changed(
            AuthorizationContext context,
            long teamId,
            long teamMemberId,
            String previousStatus,
            String newStatus,
            TeamMemberMapper.TargetMember target) {
        audit.append("MEMBERSHIP_CHANGED", context.organizationId(), context.userId(),
                "TEAM_MEMBER", teamMemberId,
                membershipMetadata(teamId, teamMemberId, previousStatus, newStatus));
        invalidation.bumpInTransaction(target.userId(), target.securityVersion());
    }

    private Map<String, Object> membershipMetadata(
            long teamId, long teamMemberId, String previousStatus, String newStatus) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("parentType", "TEAM");
        metadata.put("parentId", Long.toString(teamId));
        metadata.put("memberId", Long.toString(teamMemberId));
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
                    "Team membership validation failed",
                    "Organization member ID must be a positive decimal string.");
        }
    }

    private DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Team membership conflict", detail);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The team membership resource is not available in the current organization.");
    }
}
