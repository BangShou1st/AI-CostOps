package com.aicostops.cost.review.application;

import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateDraft;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactLineageRow;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactRow;
import com.aicostops.cost.review.domain.CandidateStatus;
import com.aicostops.cost.review.domain.CandidateType;
import com.aicostops.cost.review.domain.DuplicateCandidate;
import com.aicostops.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary of the duplicate review workflow. All reads and writes
 * are org-scoped; write helpers must be executed inside the caller's
 * transaction so keep/exclude can lock rows before mutating them.
 */
public interface DuplicateCandidateRepository {

    /** Eligible charges of one org: confirmed-attempt lineage, CLEAN/SUSPECTED only. */
    List<ChargeFactLineageRow> listEligibleCharges(long organizationId);

    /** Locks and returns charges ordered by id; result count must match the request. */
    List<ChargeFactRow> findChargesForUpdate(
            long organizationId,
            List<Long> chargeFactIds);

    /** Appends a candidate; returns 0 when the (org, pair, algorithm) row already exists. */
    int insertIgnoringDuplicate(
            CandidateDraft draft,
            Instant createdAt);

    Optional<DuplicateCandidate> findCandidateForUpdate(
            long organizationId,
            long candidateId);

    List<DuplicateCandidate> findOpenCandidatesByChargeForUpdate(
            long organizationId,
            long chargeFactId);

    /** OPEN -> KEPT_CLEAN; returns 0 when the candidate is no longer OPEN. */
    int markKeptClean(
            long organizationId,
            long candidateId,
            Instant resolvedAt);

    /** OPEN -> CONFIRMED_DUPLICATE; returns 0 when the candidate is no longer OPEN. */
    int markConfirmedDuplicate(
            long organizationId,
            long candidateId,
            Instant resolvedAt);

    /** OPEN -> SUPERSEDED for every other OPEN candidate touching the charge. */
    int supersedeOtherOpenCandidatesByCharge(
            long organizationId,
            long chargeFactId,
            long currentCandidateId,
            Instant resolvedAt);

    /** Marks the excluded charge EXCLUDED_DUPLICATE pointing at its keeper. */
    int markChargeExcluded(
            long organizationId,
            long excludedChargeFactId,
            long keeperChargeFactId);

    int countOpenCandidatesByCharge(
            long organizationId,
            long chargeFactId);

    /** Charges that already reference the keeper as their duplicate source. */
    int countInboundDuplicateReferences(
            long organizationId,
            long keeperChargeFactId);

    /** Reverts SUSPECTED_DUPLICATE -> CLEAN when no OPEN candidate remains. */
    int restoreCleanIfNoOpenCandidates(
            long organizationId,
            long chargeFactId);

    /** CLEAN -> SUSPECTED_DUPLICATE when an OPEN candidate exists in the database. */
    int markSuspectedIfHasOpenCandidate(
            long organizationId,
            long chargeFactId);

    Optional<CandidateSummary> findSummaryById(
            long organizationId,
            long candidateId);

    PageResponse<CandidateSummary> page(
            long organizationId,
            int page,
            int size,
            CandidateStatus status,
            CandidateType candidateType);
}
