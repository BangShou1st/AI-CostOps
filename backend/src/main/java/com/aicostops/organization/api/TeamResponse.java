package com.aicostops.organization.api;

import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.domain.Team;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;

public record TeamResponse(
        ApiId id,
        String code,
        String name,
        MasterDataStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static TeamResponse from(Team team) {
        return new TeamResponse(ApiId.of(team.id()), team.code(), team.name(), team.status(),
                team.createdAt(), team.updatedAt());
    }
}
