package com.aicostops.iam.infrastructure;

public record LoginIdentityRecord(
        long userId,
        String emailNormalized,
        String displayName,
        String status,
        long securityVersion,
        String passwordHash,
        long organizationMemberId,
        long organizationId) {
}
