package com.aicostops.iam.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotBlank @Size(min = 8, max = 200) String password) {
}
