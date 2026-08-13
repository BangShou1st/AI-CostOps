package com.aicostops.iam.api;

import com.aicostops.shared.json.ApiId;
import java.time.Instant;
import java.util.List;

public record UserResponse(
        ApiId id,
        String email,
        String displayName,
        String status,
        String securityVersion,
        OrganizationMemberResponse organizationMember,
        List<RoleAssignmentResponse> roleAssignments) {

    public record OrganizationMemberResponse(
            ApiId id,
            String status,
            String employeeNo,
            ApiId defaultCostCenterId) {
    }

    public record RoleAssignmentResponse(
            ApiId id,
            RoleReferenceResponse role,
            String scopeType,
            ApiId scopeId,
            Instant createdAt) {
    }

    public record RoleReferenceResponse(ApiId id, String code, String name) {
    }
}
