package com.aicostops.iam.api;

import com.aicostops.iam.application.MeService.MeResult;
import com.aicostops.shared.json.ApiId;
import java.util.List;

public record MeResponse(ApiId id, String email, String displayName, ApiId organizationId,
        ApiId organizationMemberId, List<String> permissions) {
    static MeResponse from(MeResult result) {
        return new MeResponse(ApiId.of(result.userId()), result.email(), result.displayName(),
                ApiId.of(result.organizationId()), ApiId.of(result.organizationMemberId()), result.permissions());
    }
}
