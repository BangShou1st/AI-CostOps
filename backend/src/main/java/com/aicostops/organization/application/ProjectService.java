package com.aicostops.organization.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.application.ResourceScope;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.organization.api.CreateProjectRequest;
import com.aicostops.organization.api.ProjectResponse;
import com.aicostops.organization.api.UpdateProjectRequest;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.infrastructure.ProjectMapper;
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
public class ProjectService {

    private final AuthorizationContextService authorizationContexts;
    private final ProjectMapper mapper;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ProjectService(
            AuthorizationContextService authorizationContexts,
            ProjectMapper mapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PageResponse<ProjectResponse> list(
            AuthenticatedUser authenticatedUser, MasterDataStatus status, PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        var scope = scopeParameters(authorization.requireList(context, "PROJECT_READ", ScopeType.PROJECT));
        var statusValue = status == null ? null : status.name();
        var total = mapper.countAuthorized(context.organizationId(), statusValue,
                scope.organizationWide(), scope.allowedProjectIds());
        var projects = mapper.findAuthorizedPage(context.organizationId(), statusValue,
                scope.organizationWide(), scope.allowedProjectIds(),
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(projects.stream().map(ProjectResponse::from).toList(), page, total);
    }

    @Transactional
    public ProjectResponse create(AuthenticatedUser authenticatedUser, CreateProjectRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "PROJECT_MANAGE");
        var code = normalize(request.code(), 100, "Project code");
        var name = normalize(request.name(), 200, "Project name");
        var now = clock.instant();
        try {
            if (mapper.insert(context.organizationId(), code, name, now) != 1) {
                throw new IllegalStateException("Project creation must insert exactly one row");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("Project code conflict", "The project code already exists in the current organization.");
        }
        var project = mapper.findCurrentOrganizationProject(mapper.lastInsertId(), context.organizationId());
        if (project == null) {
            throw new IllegalStateException("Created project must be readable in its organization");
        }
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse update(
            AuthenticatedUser authenticatedUser, long projectId, UpdateProjectRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        var scope = scopeParameters(authorization.requireList(context, "PROJECT_MANAGE", ScopeType.PROJECT));
        var project = mapper.findAuthorizedForUpdate(context.organizationId(), null,
                scope.organizationWide(), scope.allowedProjectIds(), projectId);
        if (project == null) {
            throw notFound();
        }
        if (request.name() == null && request.status() == null) {
            throw validationFailed("A project name or status is required.");
        }
        var name = request.name() == null ? project.name() : normalize(request.name(), 200, "Project name");
        var status = request.status() == null ? project.status() : request.status();
        if (request.status() != null && !project.status().canTransitionTo(request.status())) {
            throw conflict("Project status conflict", "The requested project status transition is not allowed.");
        }
        if (mapper.update(projectId, context.organizationId(), name, status.name(), clock.instant()) != 1) {
            throw notFound();
        }
        var updated = mapper.findCurrentOrganizationProject(projectId, context.organizationId());
        if (updated == null) {
            throw notFound();
        }
        return ProjectResponse.from(updated);
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
                "Project validation failed", detail);
    }

    private DomainException conflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT, title, detail);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The project is not available in the current organization.");
    }

    private record ScopeParameters(boolean organizationWide, List<Long> allowedProjectIds) {
    }
}
