package com.aicostops.organization.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.application.ResourceScope;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.organization.api.CreateTeamRequest;
import com.aicostops.organization.api.TeamResponse;
import com.aicostops.organization.api.UpdateTeamRequest;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.infrastructure.TeamMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    private final AuthorizationContextService authorizationContexts;
    private final TeamMapper mapper;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public TeamService(
            AuthorizationContextService authorizationContexts,
            TeamMapper mapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PageResponse<TeamResponse> list(
            AuthenticatedUser authenticatedUser, MasterDataStatus status, PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        var scope = scopeParameters(authorization.requireList(context, "TEAM_READ", ScopeType.TEAM));
        var statusValue = status == null ? null : status.name();
        var total = mapper.countAuthorized(context.organizationId(), statusValue,
                scope.organizationWide(), scope.allowedTeamIds());
        var teams = mapper.findAuthorizedPage(context.organizationId(), statusValue,
                scope.organizationWide(), scope.allowedTeamIds(),
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(teams.stream().map(TeamResponse::from).toList(), page, total);
    }

    @Transactional
    public TeamResponse create(AuthenticatedUser authenticatedUser, CreateTeamRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "TEAM_MANAGE");
        var code = normalize(request.code(), 100, "Team code");
        var name = normalize(request.name(), 200, "Team name");
        var now = clock.instant();
        try {
            if (mapper.insert(context.organizationId(), code, name, now) != 1) {
                throw new IllegalStateException("Team creation must insert exactly one row");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("Team code conflict", "The team code already exists in the current organization.");
        }
        var team = mapper.findCurrentOrganizationTeam(mapper.lastInsertId(), context.organizationId());
        if (team == null) {
            throw new IllegalStateException("Created team must be readable in its organization");
        }
        return TeamResponse.from(team);
    }

    @Transactional
    public TeamResponse update(
            AuthenticatedUser authenticatedUser, long teamId, UpdateTeamRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        var scope = scopeParameters(authorization.requireList(context, "TEAM_MANAGE", ScopeType.TEAM));
        var team = mapper.findAuthorizedForUpdate(context.organizationId(), null,
                scope.organizationWide(), scope.allowedTeamIds(), teamId);
        if (team == null) {
            throw notFound();
        }
        if (request.name() == null && request.status() == null) {
            throw validationFailed("A team name or status is required.");
        }
        var name = request.name() == null ? team.name() : normalize(request.name(), 200, "Team name");
        var status = request.status() == null ? team.status() : request.status();
        if (request.status() != null && !team.status().canTransitionTo(request.status())) {
            throw conflict("Team status conflict", "The requested team status transition is not allowed.");
        }
        if (mapper.update(teamId, context.organizationId(), name, status.name(), clock.instant()) != 1) {
            throw notFound();
        }
        var updated = mapper.findCurrentOrganizationTeam(teamId, context.organizationId());
        if (updated == null) {
            throw notFound();
        }
        return TeamResponse.from(updated);
    }

    private ScopeParameters scopeParameters(ResourceScope scope) {
        return new ScopeParameters(scope.organizationWide(), scope.resourceIds().stream().sorted().toList());
    }

    private String normalize(String value, int maxLength, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw validationFailed(field + " must be nonblank and at most " + maxLength + " characters.");
        }
        return value.trim();
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Team validation failed", detail);
    }

    private DomainException conflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT, title, detail);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The team is not available in the current organization.");
    }

    private record ScopeParameters(boolean organizationWide, List<Long> allowedTeamIds) {
    }
}
