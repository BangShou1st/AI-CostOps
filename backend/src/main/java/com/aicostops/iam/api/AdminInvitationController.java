package com.aicostops.iam.api;

import com.aicostops.iam.application.AdminInvitationService;
import com.aicostops.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class AdminInvitationController {

    private final AdminInvitationService invitations;

    public AdminInvitationController(AdminInvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateInvitationRequest request) {
        return invitations.create(authenticatedUser, request);
    }
}
