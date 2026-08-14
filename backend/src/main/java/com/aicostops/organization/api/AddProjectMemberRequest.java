package com.aicostops.organization.api;

import jakarta.validation.constraints.NotBlank;

public record AddProjectMemberRequest(@NotBlank String organizationMemberId) {
}
