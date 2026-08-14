package com.aicostops.iam.api;

import com.aicostops.shared.json.ApiId;
import java.time.Instant;

public record InvitationResponse(
        ApiId id,
        String email,
        String initialRoleCode,
        String status,
        Instant expiresAt,
        Instant createdAt) {
}
