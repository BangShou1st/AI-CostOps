package com.aicostops.organization.api;

import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.domain.Project;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;

public record ProjectResponse(
        ApiId id,
        String code,
        String name,
        MasterDataStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(ApiId.of(project.id()), project.code(), project.name(), project.status(),
                project.createdAt(), project.updatedAt());
    }
}
