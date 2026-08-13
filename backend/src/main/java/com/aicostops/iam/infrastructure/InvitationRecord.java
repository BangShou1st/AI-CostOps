package com.aicostops.iam.infrastructure;

import java.time.Instant;

public record InvitationRecord(
        long id,
        long orgId,
        String emailNormalized,
        String initialRoleCode,
        String status,
        Instant expiresAt,
        String organizationStatus) {
}
