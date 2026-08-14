package com.aicostops.organization.api;

import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.domain.ProjectMember;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;

public record ProjectMemberResponse(
        ApiId id,
        ApiId organizationMemberId,
        ApiId userId,
        String email,
        String displayName,
        String userStatus,
        MasterDataStatus status,
        Instant joinedAt) {

    public static ProjectMemberResponse from(ProjectMember member) {
        return new ProjectMemberResponse(
                ApiId.of(member.id()),
                ApiId.of(member.organizationMemberId()),
                ApiId.of(member.userId()),
                member.email(),
                member.displayName(),
                member.userStatus(),
                member.status(),
                member.joinedAt());
    }
}
