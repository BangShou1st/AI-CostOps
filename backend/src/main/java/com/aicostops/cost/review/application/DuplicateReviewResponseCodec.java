package com.aicostops.cost.review.application;

import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes the {@link CandidateSummary} stored in the idempotency table, so a
 * replay returns the cached semantic response instead of re-reading current
 * charge state that later candidates may have changed. Money travels as its
 * plain decimal string on the wire because a generic JSON number round-trip
 * does not preserve the exact database scale.
 */
@Component
public final class DuplicateReviewResponseCodec {

    private final ObjectMapper objectMapper;

    public DuplicateReviewResponseCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(CandidateSummary summary) {
        try {
            return objectMapper.writeValueAsString(StoredSummary.from(summary));
        } catch (Exception exception) {
            throw new IllegalStateException("Candidate summary serialization failed", exception);
        }
    }

    public CandidateSummary fromJson(String json) {
        try {
            return objectMapper.readValue(json, StoredSummary.class).toSummary();
        } catch (Exception exception) {
            throw new IllegalStateException("Stored candidate summary is not valid", exception);
        }
    }

    private record StoredSummary(
            long id,
            long organizationId,
            long chargeFactId,
            long matchedChargeId,
            String candidateType,
            String fingerprint,
            String algorithmVersion,
            String matchReason,
            String status,
            Instant createdAt,
            Instant resolvedAt,
            StoredCharge chargeFact,
            StoredCharge matchedChargeFact) {

        static StoredSummary from(CandidateSummary summary) {
            var candidate = summary.candidate();
            return new StoredSummary(
                    candidate.id(), candidate.organizationId(), candidate.chargeFactId(),
                    candidate.matchedChargeId(), candidate.candidateType().name(), candidate.fingerprint(),
                    candidate.algorithmVersion(), candidate.matchReason(), candidate.status().name(),
                    candidate.createdAt(), candidate.resolvedAt(),
                    StoredCharge.from(summary.chargeFact()), StoredCharge.from(summary.matchedChargeFact()));
        }

        CandidateSummary toSummary() {
            return new CandidateSummary(
                    new com.aicostops.cost.review.domain.DuplicateCandidate(id, organizationId,
                            chargeFactId, matchedChargeId,
                            com.aicostops.cost.review.domain.CandidateType.valueOf(candidateType),
                            fingerprint, algorithmVersion, matchReason,
                            com.aicostops.cost.review.domain.CandidateStatus.valueOf(status),
                            createdAt, resolvedAt),
                    chargeFact.toCharge(),
                    matchedChargeFact.toCharge());
        }
    }

    private record StoredCharge(
            long id,
            String providerCode,
            String chargeCategory,
            String amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            String reviewStatus,
            Long duplicateOfChargeId) {

        static StoredCharge from(DuplicateReviewReadModels.ChargeSummary charge) {
            return new StoredCharge(charge.id(), charge.providerCode(), charge.chargeCategory(),
                    charge.amount().toPlainString(), charge.currency(), charge.periodStart(),
                    charge.periodEnd(), charge.reviewStatus().name(), charge.duplicateOfChargeId());
        }

        DuplicateReviewReadModels.ChargeSummary toCharge() {
            return new DuplicateReviewReadModels.ChargeSummary(id, providerCode, chargeCategory,
                    new BigDecimal(amount), currency, periodStart, periodEnd,
                    com.aicostops.cost.domain.ReviewStatus.valueOf(reviewStatus), duplicateOfChargeId);
        }
    }
}
