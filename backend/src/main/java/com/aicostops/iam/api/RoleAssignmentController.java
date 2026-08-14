package com.aicostops.iam.api;

import com.aicostops.iam.application.RoleAssignmentService;
import com.aicostops.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/role-assignments")
public class RoleAssignmentController {

    private final RoleAssignmentService roleAssignments;

    public RoleAssignmentController(RoleAssignmentService roleAssignments) {
        this.roleAssignments = roleAssignments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse.RoleAssignmentResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateRoleAssignmentRequest request) {
        return roleAssignments.create(authenticatedUser, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id) {
        roleAssignments.revoke(authenticatedUser, id);
    }
}
