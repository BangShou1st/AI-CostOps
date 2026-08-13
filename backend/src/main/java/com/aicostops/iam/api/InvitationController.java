package com.aicostops.iam.api;

import com.aicostops.iam.application.AcceptInvitationCommand;
import com.aicostops.iam.application.InvitationAcceptanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationAcceptanceService invitationAcceptanceService;

    public InvitationController(InvitationAcceptanceService invitationAcceptanceService) {
        this.invitationAcceptanceService = invitationAcceptanceService;
    }

    @PostMapping("/{token}/accept")
    public RegisteredIdentityResponse accept(
            @PathVariable String token,
            @Valid @RequestBody AcceptInvitationRequest request) {
        return RegisteredIdentityResponse.from(invitationAcceptanceService.accept(token,
                new AcceptInvitationCommand(request.displayName(), request.password())));
    }
}
