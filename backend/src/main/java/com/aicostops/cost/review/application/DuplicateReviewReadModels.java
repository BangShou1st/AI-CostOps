package com.aicostops.cost.review.application;

import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.review.domain.CandidateType;
import com.aicostops.cost.review.domain.DuplicateCandidate;
import java.math.BigDecimal;
import java.time.Instant;

/** Read models shared by the duplicate review scan, command, and query paths. */
public final class DuplicateReviewReadModels {

    private DuplicateReviewReadModels() {
    }

    /** Eligible charge with its confirmed-import lineage for candidate generation. */
    public record ChargeFactLineageRow(
            long id,
            long organizationId,
            long providerAccountId,
            String providerCode,
            String chargeCategory,
            BigDecimal amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus) {
    }

    /** Locked current charge state used by keep/exclude guards. */
    public record ChargeFactRow(
            long id,
            long organizationId,
            ReviewStatus reviewStatus,
            Long duplicateOfChargeId) {
    }

    /** Candidate row to append; pair ordering and identity are enforced by the database. */
    public record CandidateDraft(
            long organizationId,
            long chargeFactId,
            long matchedChargeId,
            CandidateType candidateType,
            String fingerprint,
            String algorithmVersion,
            String matchReason) {
    }

    /** Charge projection returned next to a candidate. */
    public record ChargeSummary(
            long id,
            String providerCode,
            String chargeCategory,
            BigDecimal amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus,
            Long duplicateOfChargeId) {

        public String reviewStatusName() {
            return reviewStatus.name();
        }
    }

    /** Candidate plus both endpoint charges; the idempotent command response model. */
    public record CandidateSummary(
            DuplicateCandidate candidate,
            ChargeSummary chargeFact,
            ChargeSummary matchedChargeFact) {
    }

    /** Aggregate result of one org-level scan run. */
    public record DuplicateScanSummary(
            long chargesScanned,
            long candidatePairsEvaluated,
            long candidatesCreated,
            long candidatesAlreadyPresent,
            Instant scannedAt) {
    }
}
