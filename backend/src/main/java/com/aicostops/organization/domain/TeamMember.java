package com.aicostops.organization.domain;

import java.time.Instant;

public record TeamMember(
        long id,
        long teamId,
        long organizationMemberId,
        long userId,
        String email,
        String displayName,
        String userStatus,
        MasterDataStatus status,
        Instant joinedAt) {
}
