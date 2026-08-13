package com.aicostops.iam.api;

import com.aicostops.iam.api.UpdateUserStatusRequest.DecimalStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateRoleAssignmentRequest(
        @NotBlank @Pattern(regexp = "[1-9][0-9]*")
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        String organizationMemberId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*")
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        String roleId,
        @NotBlank String scopeType,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*")
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        String scopeId) {
}
