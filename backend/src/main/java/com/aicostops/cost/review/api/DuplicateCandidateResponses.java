package com.aicostops.cost.review.api;

import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeSummary;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.DuplicateScanSummary;
import com.aicostops.shared.json.ApiId;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Browser-facing DTOs of the duplicate review API; ids and money are strings. */
public final class DuplicateCandidateResponses {

    private DuplicateCandidateResponses() {
    }

    public record DuplicateExcludeRequest(@NotNull ApiId excludedChargeFactId) {
    }

    public record ChargeFactRefResponse(
            ApiId id,
            String providerCode,
            String chargeCategory,
            String amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            String reviewStatus,
            ApiId duplicateOfChargeId) {

        static ChargeFactRefResponse from(ChargeSummary charge) {
            return new ChargeFactRefResponse(
                    ApiId.of(charge.id()),
                    charge.providerCode(),
                    charge.chargeCategory(),
                    charge.amount().toPlainString(),
                    charge.currency(),
                    charge.periodStart(),
                    charge.periodEnd(),
                    charge.reviewStatusName(),
                    charge.duplicateOfChargeId() == null ? null : ApiId.of(charge.duplicateOfChargeId()));
        }
    }

    public record DuplicateCandidateResponse(
            ApiId id,
            String candidateType,
            String fingerprint,
            String algorithmVersion,
            String matchReason,
            String status,
            ChargeFactRefResponse chargeFact,
            ChargeFactRefResponse matchedChargeFact,
            Instant createdAt,
            Instant resolvedAt) {

        public static DuplicateCandidateResponse from(CandidateSummary summary) {
            var candidate = summary.candidate();
            return new DuplicateCandidateResponse(
                    ApiId.of(candidate.id()),
                    candidate.candidateType().name(),
                    candidate.fingerprint(),
                    candidate.algorithmVersion(),
                    candidate.matchReason(),
                    candidate.status().name(),
                    ChargeFactRefResponse.from(summary.chargeFact()),
                    ChargeFactRefResponse.from(summary.matchedChargeFact()),
                    candidate.createdAt(),
                    candidate.resolvedAt());
        }
    }

    public record DuplicateScanResponse(
            long chargesScanned,
            long candidatePairsEvaluated,
            long candidatesCreated,
            long candidatesAlreadyPresent,
            Instant scannedAt) {

        public static DuplicateScanResponse from(DuplicateScanSummary summary) {
            return new DuplicateScanResponse(summary.chargesScanned(), summary.candidatePairsEvaluated(),
                    summary.candidatesCreated(), summary.candidatesAlreadyPresent(), summary.scannedAt());
        }
    }
}
