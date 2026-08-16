package com.aicostops.cost.review.infrastructure;

import com.aicostops.cost.review.application.DuplicateCandidateRepository;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateDraft;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactLineageRow;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactRow;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeSummary;
import com.aicostops.cost.review.domain.CandidateStatus;
import com.aicostops.cost.review.domain.CandidateType;
import com.aicostops.cost.review.domain.DuplicateCandidate;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisDuplicateCandidateRepository implements DuplicateCandidateRepository {

    private final DuplicateCandidateMapper mapper;

    public MyBatisDuplicateCandidateRepository(DuplicateCandidateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ChargeFactLineageRow> listEligibleCharges(long organizationId) {
        return mapper.selectEligibleLineage(organizationId);
    }

    @Override
    public List<ChargeFactRow> findChargesForUpdate(long organizationId, List<Long> chargeFactIds) {
        if (chargeFactIds.isEmpty()) {
            return List.of();
        }
        var locked = mapper.selectChargesForUpdate(organizationId, chargeFactIds);
        if (locked.size() != chargeFactIds.size()) {
            throw new IllegalStateException(
                    "Locked charge rows must exactly match the requested endpoint ids");
        }
        return locked;
    }

    @Override
    public int insertIgnoringDuplicate(CandidateDraft draft, Instant createdAt) {
        // A plain INSERT plus DuplicateKeyException mapping keeps FK/CHECK
        // violations loud while making an existing (org, pair, algorithm) row
        // a no-op — INSERT IGNORE itself would also swallow tenant violations.
        try {
            return mapper.insert(
                    draft.organizationId(),
                    draft.chargeFactId(),
                    draft.matchedChargeId(),
                    draft.candidateType().name(),
                    draft.fingerprint(),
                    draft.algorithmVersion(),
                    draft.matchReason(),
                    createdAt);
        } catch (DuplicateKeyException alreadyPresent) {
            return 0;
        }
    }

    @Override
    public Optional<DuplicateCandidate> findCandidateForUpdate(long organizationId, long candidateId) {
        return Optional.ofNullable(mapper.selectByIdForUpdate(organizationId, candidateId));
    }

    @Override
    public List<DuplicateCandidate> findOpenCandidatesByChargeForUpdate(long organizationId,
            long chargeFactId) {
        return mapper.selectOpenByChargeForUpdate(organizationId, chargeFactId);
    }

    @Override
    public int markKeptClean(long organizationId, long candidateId, Instant resolvedAt) {
        return mapper.markKeptClean(organizationId, candidateId, resolvedAt);
    }

    @Override
    public int markConfirmedDuplicate(long organizationId, long candidateId, Instant resolvedAt) {
        return mapper.markConfirmedDuplicate(organizationId, candidateId, resolvedAt);
    }

    @Override
    public int supersedeOtherOpenCandidatesByCharge(long organizationId, long chargeFactId,
            long currentCandidateId, Instant resolvedAt) {
        return mapper.supersedeOtherOpenByCharge(organizationId, chargeFactId, currentCandidateId,
                resolvedAt);
    }

    @Override
    public int markChargeExcluded(long organizationId, long excludedChargeFactId,
            long keeperChargeFactId) {
        return mapper.markChargeExcluded(organizationId, excludedChargeFactId, keeperChargeFactId);
    }

    @Override
    public int countOpenCandidatesByCharge(long organizationId, long chargeFactId) {
        return mapper.countOpenByCharge(organizationId, chargeFactId);
    }

    @Override
    public int countInboundDuplicateReferences(long organizationId, long keeperChargeFactId) {
        return mapper.countInboundDuplicateReferences(organizationId, keeperChargeFactId);
    }

    @Override
    public int restoreCleanIfNoOpenCandidates(long organizationId, long chargeFactId) {
        return mapper.restoreCleanIfNoOpen(organizationId, chargeFactId);
    }

    @Override
    public int markSuspectedIfHasOpenCandidate(long organizationId, long chargeFactId) {
        return mapper.markSuspectedIfOpenExists(organizationId, chargeFactId);
    }

    @Override
    public Optional<CandidateSummary> findSummaryById(long organizationId, long candidateId) {
        var candidate = mapper.selectById(organizationId, candidateId);
        if (candidate == null) {
            return Optional.empty();
        }
        var charges = chargeSummaries(organizationId,
                List.of(candidate.chargeFactId(), candidate.matchedChargeId()));
        return Optional.of(new CandidateSummary(candidate,
                charges.get(candidate.chargeFactId()), charges.get(candidate.matchedChargeId())));
    }

    @Override
    public PageResponse<CandidateSummary> page(long organizationId, int page, int size,
            CandidateStatus status, CandidateType candidateType) {
        var request = PageRequest.of(page, size);
        var statusName = status == null ? null : status.name();
        var typeName = candidateType == null ? null : candidateType.name();
        var candidates = mapper.pageCandidates(organizationId, statusName, typeName,
                (long) request.page() * request.size(), request.size());
        var summaries = candidates.isEmpty() ? List.<CandidateSummary>of()
                : candidates.stream().map(candidate -> {
                    var charges = chargeSummaries(organizationId,
                            List.of(candidate.chargeFactId(), candidate.matchedChargeId()));
                    return new CandidateSummary(candidate,
                            charges.get(candidate.chargeFactId()),
                            charges.get(candidate.matchedChargeId()));
                }).toList();
        var total = mapper.countCandidates(organizationId, statusName, typeName);
        return PageResponse.of(summaries, request, total);
    }

    private Map<Long, ChargeSummary> chargeSummaries(long organizationId, List<Long> chargeFactIds) {
        return mapper.selectChargeSummaries(organizationId, chargeFactIds).stream()
                .collect(Collectors.toMap(ChargeSummary::id, Function.identity()));
    }
}
