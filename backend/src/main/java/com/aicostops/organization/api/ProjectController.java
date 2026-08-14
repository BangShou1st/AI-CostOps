package com.aicostops.organization.api;

import com.aicostops.organization.application.ProjectMembershipService;
import com.aicostops.organization.application.ProjectService;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectMembershipService memberships;

    public ProjectController(ProjectService projects, ProjectMembershipService memberships) {
        this.projects = projects;
        this.memberships = memberships;
    }

    @GetMapping
    public PageResponse<ProjectResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) MasterDataStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return projects.list(authenticatedUser, status, PageRequest.of(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateProjectRequest request) {
        return projects.create(authenticatedUser, request);
    }

    @PatchMapping("/{id}")
    public ProjectResponse update(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @Valid @RequestBody UpdateProjectRequest request) {
        return projects.update(authenticatedUser, id, request);
    }

    @GetMapping("/{id}/members")
    public PageResponse<ProjectMemberResponse> listMembers(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @RequestParam(required = false) MasterDataStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return memberships.list(authenticatedUser, id, status, PageRequest.of(page, size));
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @Valid @RequestBody AddProjectMemberRequest request) {
        return memberships.add(authenticatedUser, id, request);
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @PathVariable long memberId) {
        memberships.remove(authenticatedUser, id, memberId);
    }
}
