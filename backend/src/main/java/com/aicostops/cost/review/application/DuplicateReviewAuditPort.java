package com.aicostops.cost.review.application;

import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;

/**
 * Cost-review-owned audit boundary for duplicate review commands. Metadata must
 * stay secret-free (identifiers and status names only); an insertion failure
 * rolls back the whole command transaction.
 */
public interface DuplicateReviewAuditPort {

    void candidateKeptClean(
            long organizationId,
            long actorUserId,
            CandidateSummary before,
            CandidateSummary after);

    void candidateExcluded(
            long organizationId,
            long actorUserId,
            long candidateId,
            long excludedChargeFactId,
            long keeperChargeFactId,
            ReviewStatus previousReviewStatus,
            int supersededCandidateCount);
}
