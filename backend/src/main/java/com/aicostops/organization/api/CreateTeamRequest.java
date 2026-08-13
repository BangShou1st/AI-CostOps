package com.aicostops.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTeamRequest(
        @NotBlank @Size(max = 100) String code,
        @NotBlank @Size(max = 200) String name) {
}
