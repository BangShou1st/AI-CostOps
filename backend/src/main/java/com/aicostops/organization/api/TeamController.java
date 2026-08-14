package com.aicostops.organization.api;

import com.aicostops.organization.application.TeamMembershipService;
import com.aicostops.organization.application.TeamService;
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
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teams;
    private final TeamMembershipService memberships;

    public TeamController(TeamService teams, TeamMembershipService memberships) {
        this.teams = teams;
        this.memberships = memberships;
    }

    @GetMapping
    public PageResponse<TeamResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) MasterDataStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return teams.list(authenticatedUser, status, PageRequest.of(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateTeamRequest request) {
        return teams.create(authenticatedUser, request);
    }

    @PatchMapping("/{id}")
    public TeamResponse update(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @Valid @RequestBody UpdateTeamRequest request) {
        return teams.update(authenticatedUser, id, request);
    }

    @GetMapping("/{id}/members")
    public PageResponse<TeamMemberResponse> listMembers(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @RequestParam(required = false) MasterDataStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return memberships.list(authenticatedUser, id, status, PageRequest.of(page, size));
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamMemberResponse addMember(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @Valid @RequestBody AddTeamMemberRequest request) {
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
