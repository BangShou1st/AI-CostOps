package com.aicostops.iam.application;

import com.aicostops.iam.infrastructure.IssuedAccessToken;

public record LoginResult(
        IssuedAccessToken accessToken,
        String refreshCredential,
        long userId,
        String displayName,
        long organizationMemberId,
        long organizationId) {
}
