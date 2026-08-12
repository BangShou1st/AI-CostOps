package com.aicostops.iam.infrastructure;

public record RefreshSessionData(long userId, long organizationMemberId, long securityVersion) {
}
