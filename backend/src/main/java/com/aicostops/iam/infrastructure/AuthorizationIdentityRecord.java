package com.aicostops.iam.infrastructure;

public record AuthorizationIdentityRecord(
        long userId, long securityVersion, long organizationMemberId, long organizationId) {
}
