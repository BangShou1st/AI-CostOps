package com.aicostops.iam.api;

import com.aicostops.iam.application.RegisteredIdentity;
import com.aicostops.shared.json.ApiId;

public record RegisteredIdentityResponse(
        ApiId userId,
        ApiId organizationMemberId,
        ApiId organizationId) {

    public static RegisteredIdentityResponse from(RegisteredIdentity identity) {
        return new RegisteredIdentityResponse(
                ApiId.of(identity.userId()),
                ApiId.of(identity.organizationMemberId()),
                ApiId.of(identity.organizationId()));
    }
}
