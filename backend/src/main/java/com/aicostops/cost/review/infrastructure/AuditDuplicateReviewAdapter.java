package com.aicostops.cost.review.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.review.application.DuplicateReviewAuditPort;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Delegates duplicate review audit events to the low-level {@link AuditService}.
 * Metadata carries only identifiers, enum names, and counts; provider values
 * and raw payloads are never part of an audit event.
 */
@Component
public class AuditDuplicateReviewAdapter implements DuplicateReviewAuditPort {

    private static final String SUBJECT_TYPE_DUPLICATE_CANDIDATE = "DUPLICATE_CANDIDATE";
    private static final String SUBJECT_TYPE_CHARGE_FACT = "CHARGE_FACT";

    private final AuditService auditService;

    public AuditDuplicateReviewAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void candidateKeptClean(long organizationId, long actorUserId,
            CandidateSummary before, CandidateSummary after) {
        auditService.append("DUPLICATE_CANDIDATE_KEPT_CLEAN", organizationId, actorUserId,
                SUBJECT_TYPE_DUPLICATE_CANDIDATE, before.candidate().id(),
                Map.of(
                        "chargeFactId", before.candidate().chargeFactId(),
                        "matchedChargeFactId", before.candidate().matchedChargeId(),
                        "chargeFactPreviousReviewStatus", before.chargeFact().reviewStatus().name(),
                        "chargeFactNewReviewStatus", after.chargeFact().reviewStatus().name(),
                        "matchedPreviousReviewStatus", before.matchedChargeFact().reviewStatus().name(),
                        "matchedNewReviewStatus", after.matchedChargeFact().reviewStatus().name()));
    }

    @Override
    public void candidateExcluded(long organizationId, long actorUserId, long candidateId,
            long excludedChargeFactId, long keeperChargeFactId, ReviewStatus previousReviewStatus,
            int supersededCandidateCount) {
        auditService.append("DUPLICATE_CANDIDATE_EXCLUDED", organizationId, actorUserId,
                SUBJECT_TYPE_CHARGE_FACT, excludedChargeFactId,
                Map.of(
                        "candidateId", candidateId,
                        "keptChargeFactId", keeperChargeFactId,
                        "previousReviewStatus", previousReviewStatus.name(),
                        "newReviewStatus", ReviewStatus.EXCLUDED_DUPLICATE.name(),
                        "supersededCandidateCount", supersededCandidateCount));
    }
}
