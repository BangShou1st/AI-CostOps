package com.aicostops.organization.domain;

import java.time.Instant;

public record ProjectMember(
        long id,
        long projectId,
        long organizationMemberId,
        long userId,
        String email,
        String displayName,
        String userStatus,
        MasterDataStatus status,
        Instant joinedAt) {
}
