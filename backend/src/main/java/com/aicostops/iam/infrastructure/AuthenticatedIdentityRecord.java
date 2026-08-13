package com.aicostops.iam.infrastructure;

public record AuthenticatedIdentityRecord(
        long userId, String emailNormalized, String displayName, String status,
        long securityVersion, long organizationMemberId, long organizationId) {
}
