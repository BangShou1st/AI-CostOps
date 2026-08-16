package com.aicostops.cost.review.domain;

import java.time.Instant;

/**
 * One candidate duplicate relation between two charges of one organization.
 * {@code chargeFactId} is always the lower charge id; pair identity per
 * algorithm version is enforced by the database.
 */
public record DuplicateCandidate(
        long id,
        long organizationId,
        long chargeFactId,
        long matchedChargeId,
        CandidateType candidateType,
        String fingerprint,
        String algorithmVersion,
        String matchReason,
        CandidateStatus status,
        Instant createdAt,
        Instant resolvedAt) {
}
