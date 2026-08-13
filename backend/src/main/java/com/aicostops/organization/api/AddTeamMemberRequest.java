package com.aicostops.organization.api;

import jakarta.validation.constraints.NotBlank;

public record AddTeamMemberRequest(@NotBlank String organizationMemberId) {
}
