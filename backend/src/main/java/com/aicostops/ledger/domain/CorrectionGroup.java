package com.aicostops.ledger.domain;

import java.time.Instant;

public record CorrectionGroup(
        long id,
        long organizationId,
        String correctionKey,
        String reasonCode,
        String reasonText,
        long targetEntryId,
        long targetPostingId,
        String status,
        long createdByMemberId,
        Instant createdAt) {
}
