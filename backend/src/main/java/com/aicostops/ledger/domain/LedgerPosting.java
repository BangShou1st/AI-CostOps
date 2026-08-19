package com.aicostops.ledger.domain;

import java.time.Instant;

public record LedgerPosting(
        long id,
        long organizationId,
        String postingKey,
        LedgerSourceType sourceType,
        long sourceId,
        Long allocationDecisionId,
        long billingPeriodId,
        String status,
        long postedByMemberId,
        Instant postedAt,
        Instant createdAt) {
}
