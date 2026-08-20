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

    public record ChargeFactRow(
            long id,
            long organizationId,
            ReviewStatus reviewStatus,
            Long duplicateOfChargeId) {
    }

    /**
     * Effective instants are carried only for Close admission and are not
     * persisted on duplicate_candidate. The seven-argument constructor keeps
     * direct persistence tests/backward callers source-compatible.
     */
    public record CandidateDraft(
            long organizationId,
            long chargeFactId,
            long matchedChargeId,
            CandidateType candidateType,
            String fingerprint,
            String algorithmVersion,
            String matchReason,
            Instant chargeEffectiveAt,
            Instant matchedEffectiveAt) {

        public CandidateDraft(
                long organizationId,
                long chargeFactId,
                long matchedChargeId,
                CandidateType candidateType,
                String fingerprint,
                String algorithmVersion,
                String matchReason) {
            this(organizationId, chargeFactId, matchedChargeId, candidateType, fingerprint,
                    algorithmVersion, matchReason, null, null);
        }
    }

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

    public record CandidateSummary(
            DuplicateCandidate candidate,
            ChargeSummary chargeFact,
            ChargeSummary matchedChargeFact) {
    }

    public record DuplicateScanSummary(
            long chargesScanned,
            long candidatePairsEvaluated,
            long candidatesCreated,
            long candidatesAlreadyPresent,
            Instant scannedAt) {
    }
}
