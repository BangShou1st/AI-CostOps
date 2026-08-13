package com.aicostops.iam.api;

import com.aicostops.iam.infrastructure.AuthenticatedIdentityRecord;
import com.aicostops.shared.json.ApiId;

public record MeResponse(ApiId id, String email, String displayName, ApiId organizationId,
        ApiId organizationMemberId) {
    static MeResponse from(AuthenticatedIdentityRecord identity) {
        return new MeResponse(ApiId.of(identity.userId()), identity.emailNormalized(), identity.displayName(),
                ApiId.of(identity.organizationId()), ApiId.of(identity.organizationMemberId()));
    }
}
