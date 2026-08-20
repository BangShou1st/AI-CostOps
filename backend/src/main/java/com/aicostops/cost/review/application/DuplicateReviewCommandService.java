package com.aicostops.cost.review.application;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateDraft;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactLineageRow;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactRow;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.DuplicateScanSummary;
import com.aicostops.cost.review.domain.CandidateStatus;
import com.aicostops.cost.review.domain.CandidateType;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DuplicateReviewCommandService {

    private static final int SCAN_BATCH_SIZE = 500;
    private static final int DEADLOCK_RETRIES = 3;
    private static final String PERMISSION_DUPLICATE_REVIEW = "DUPLICATE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final DuplicateCandidateRepository candidates;
    private final DuplicateReviewIdempotency idempotency;
    private final DuplicateReviewAuditPort audit;
    private final DuplicateReviewResponseCodec responseCodec;
    private final BillingPeriodFinancialWriteFence periodFence;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DuplicateReviewCommandService(
            AuthorizationContextService authorizationContexts,
            DuplicateCandidateRepository candidates,
            DuplicateReviewIdempotency idempotency,
            DuplicateReviewAuditPort audit,
            DuplicateReviewResponseCodec responseCodec,
            BillingPeriodFinancialWriteFence periodFence,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.candidates = candidates;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.periodFence = periodFence;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public DuplicateScanSummary scan(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_DUPLICATE_REVIEW);
        var organizationId = context.organizationId();

        var eligible = candidates.listEligibleCharges(organizationId);
        var drafts = new ArrayList<CandidateDraft>();
        long pairsEvaluated = generateCandidates(eligible, drafts);

        long candidatesCreated = 0;
        long candidatesAlreadyPresent = 0;
        var scannedAt = clock.instant();
        for (int from = 0; from < drafts.size(); from += SCAN_BATCH_SIZE) {
            var batch = drafts.subList(from, Math.min(from + SCAN_BATCH_SIZE, drafts.size()));
            var committed = executeWithDeadlockRetry(
                    () -> transactions.execute(status -> writeBatch(organizationId, batch)));
            candidatesCreated += committed.candidatesCreated();
            candidatesAlreadyPresent += committed.candidatesAlreadyPresent();
        }
        return new DuplicateScanSummary(eligible.size(), pairsEvaluated,
                candidatesCreated, candidatesAlreadyPresent, scannedAt);
    }

    public CandidateSummary keep(AuthenticatedUser user, long candidateId, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_DUPLICATE_REVIEW);
        idempotency.validateKey(idempotencyKey);
        requireCandidateVisible(context.organizationId(), candidateId);

        var requestHash = idempotency.keepRequestHash(context.organizationId(),
                context.organizationMemberId(), candidateId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(), context.organizationMemberId(),
                    DuplicateReviewIdempotency.OPERATION_KEEP, idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }

            // Keep only removes an OPEN blocker; it remains legal in CLOSING.
            var candidate = candidates.findCandidateForUpdate(context.organizationId(), candidateId)
                    .orElseThrow(DuplicateReviewCommandService::candidateNotFound);
            if (candidate.status() != CandidateStatus.OPEN) {
                throw candidateNotOpen();
            }
            lockCharges(context.organizationId(),
                    List.of(candidate.chargeFactId(), candidate.matchedChargeId()));
            var before = candidates.findSummaryById(context.organizationId(), candidateId)
                    .orElseThrow(() -> new IllegalStateException("A locked candidate must be readable"));

            if (candidates.markKeptClean(context.organizationId(), candidateId, clock.instant()) != 1) {
                throw candidateNotOpen();
            }
            candidates.restoreCleanIfNoOpenCandidates(context.organizationId(), candidate.chargeFactId());
            candidates.restoreCleanIfNoOpenCandidates(context.organizationId(), candidate.matchedChargeId());

            var after = candidates.findSummaryById(context.organizationId(), candidateId)
                    .orElseThrow(() -> new IllegalStateException("A resolved candidate must be readable"));
            audit.candidateKeptClean(context.organizationId(), context.userId(), before, after);
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(after));
            return after;
        }));
    }

    public CandidateSummary exclude(AuthenticatedUser user, long candidateId, String idempotencyKey,
            long excludedChargeFactId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_DUPLICATE_REVIEW);
        idempotency.validateKey(idempotencyKey);
        var candidate = requireCandidateVisible(context.organizationId(), candidateId);
        if (candidate.candidate().chargeFactId() != excludedChargeFactId
                && candidate.candidate().matchedChargeId() != excludedChargeFactId) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Excluded charge is not part of the candidate",
                    "excludedChargeFactId must be one of the candidate's two charges.");
        }

        var requestHash = idempotency.excludeRequestHash(context.organizationId(),
                context.organizationMemberId(), candidateId, excludedChargeFactId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(), context.organizationMemberId(),
                    DuplicateReviewIdempotency.OPERATION_EXCLUDE, idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }

            // Exclude changes which canonical Charge money belongs to external
            // truth, so a new exclusion must serialize with Close.
            periodFence.lockOrganizationAndRequireNoClosingPeriod(context.organizationId());
            var locked = candidates.findCandidateForUpdate(context.organizationId(), candidateId)
                    .orElseThrow(DuplicateReviewCommandService::candidateNotFound);
            if (locked.status() != CandidateStatus.OPEN) {
                throw candidateNotOpen();
            }
            var keeperChargeFactId = locked.chargeFactId() == excludedChargeFactId
                    ? locked.matchedChargeId()
                    : locked.chargeFactId();

            var touching = candidates.findOpenCandidatesByChargeForUpdate(context.organizationId(),
                    excludedChargeFactId);
            var affected = new TreeSet<Long>();
            affected.add(excludedChargeFactId);
            affected.add(keeperChargeFactId);
            for (var open : touching) {
                affected.add(open.chargeFactId());
                affected.add(open.matchedChargeId());
            }
            var charges = lockCharges(context.organizationId(), new ArrayList<>(affected));
            var excludedCharge = charges.get(excludedChargeFactId);
            var keeperCharge = charges.get(keeperChargeFactId);
            requireExcludeCandidate(keeperCharge);
            requireExcludeCandidate(excludedCharge);
            if (candidates.countInboundDuplicateReferences(context.organizationId(),
                    excludedChargeFactId) > 0) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Charge cannot be excluded",
                        "The charge is already the keeper of other excluded duplicates; excluding it would create a chain.");
            }
            var previousReviewStatus = excludedCharge.reviewStatus();
            var before = candidates.findSummaryById(context.organizationId(), candidateId)
                    .orElseThrow(() -> new IllegalStateException("A locked candidate must be readable"));

            if (candidates.markChargeExcluded(context.organizationId(), excludedChargeFactId,
                    keeperChargeFactId) != 1) {
                throw new IllegalStateException("Excluding a guarded charge must update exactly one row");
            }
            if (candidates.markConfirmedDuplicate(context.organizationId(), candidateId,
                    clock.instant()) != 1) {
                throw candidateNotOpen();
            }
            var supersededCandidateCount = candidates.supersedeOtherOpenCandidatesByCharge(
                    context.organizationId(), excludedChargeFactId, candidateId, clock.instant());

            for (var endpointId : affected) {
                if (endpointId != excludedChargeFactId) {
                    candidates.restoreCleanIfNoOpenCandidates(context.organizationId(), endpointId);
                }
            }

            var after = candidates.findSummaryById(context.organizationId(), candidateId)
                    .orElseThrow(() -> new IllegalStateException("A resolved candidate must be readable"));
            audit.candidateExcluded(context.organizationId(), context.userId(), candidateId,
                    excludedChargeFactId, keeperChargeFactId, previousReviewStatus,
                    supersededCandidateCount);
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(after));
            return after;
        }));
    }

    private CandidateSummary requireCandidateVisible(long organizationId, long candidateId) {
        return candidates.findSummaryById(organizationId, candidateId)
                .orElseThrow(DuplicateReviewCommandService::candidateNotFound);
    }

    private Map<Long, ChargeFactRow> lockCharges(long organizationId, List<Long> chargeFactIds) {
        var ordered = chargeFactIds.stream().distinct().sorted().toList();
        return candidates.findChargesForUpdate(organizationId, ordered).stream()
                .collect(Collectors.toMap(ChargeFactRow::id, row -> row));
    }

    private static void requireExcludeCandidate(ChargeFactRow charge) {
        if (charge == null || charge.duplicateOfChargeId() != null
                || (charge.reviewStatus() != ReviewStatus.CLEAN
                        && charge.reviewStatus() != ReviewStatus.SUSPECTED_DUPLICATE)) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Charge cannot take part in an exclude decision",
                    "Both candidate endpoints must be CLEAN or SUSPECTED_DUPLICATE without an existing duplicate pointer.");
        }
    }

    private static DomainException candidateNotOpen() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Candidate is not open",
                "Only an OPEN candidate can be resolved by a keep or exclude decision.");
    }

    private static DomainException candidateNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Duplicate candidate not found",
                "The duplicate candidate is not available in the current organization.");
    }

    private record BatchWriteResult(long candidatesCreated, long candidatesAlreadyPresent) {
    }

    private BatchWriteResult writeBatch(long organizationId, List<CandidateDraft> batch) {
        // Each persistence chunk is a short organization-admission transaction.
        // If Close has already switched any period to CLOSING, no new OPEN
        // candidate may be created after that point.
        periodFence.lockOrganizationAndRequireNoClosingPeriod(organizationId);

        var endpointIds = batch.stream()
                .flatMap(draft -> Stream.of(draft.chargeFactId(), draft.matchedChargeId()))
                .distinct()
                .sorted()
                .toList();
        var stillEligible = candidates.findChargesForUpdate(organizationId, endpointIds).stream()
                .filter(charge -> charge.reviewStatus() == ReviewStatus.CLEAN
                        || charge.reviewStatus() == ReviewStatus.SUSPECTED_DUPLICATE)
                .map(ChargeFactRow::id)
                .collect(Collectors.toSet());

        long created = 0;
        long alreadyPresent = 0;
        var now = clock.instant();
        for (var draft : batch) {
            if (!stillEligible.contains(draft.chargeFactId())
                    || !stillEligible.contains(draft.matchedChargeId())) {
                continue;
            }
            if (candidates.insertIgnoringDuplicate(draft, now) == 1) {
                created++;
            } else {
                alreadyPresent++;
            }
        }
        for (var endpointId : stillEligible) {
            candidates.markSuspectedIfHasOpenCandidate(organizationId, endpointId);
        }
        return new BatchWriteResult(created, alreadyPresent);
    }

    private long generateCandidates(List<ChargeFactLineageRow> eligible, List<CandidateDraft> drafts) {
        var groups = eligible.stream().collect(Collectors.groupingBy(
                row -> List.of(row.providerAccountId(), row.providerCode(), row.chargeCategory(),
                        row.currency()),
                LinkedHashMap::new,
                Collectors.toList()));
        long pairsEvaluated = 0;
        for (var group : groups.values()) {
            var charges = group.stream()
                    .sorted(Comparator.comparingLong(ChargeFactLineageRow::id))
                    .toList();
            for (int i = 0; i < charges.size(); i++) {
                for (int j = i + 1; j < charges.size(); j++) {
                    pairsEvaluated++;
                    buildCandidate(charges.get(i), charges.get(j)).ifPresent(drafts::add);
                }
            }
        }
        return pairsEvaluated;
    }

    private Optional<CandidateDraft> buildCandidate(ChargeFactLineageRow low, ChargeFactLineageRow high) {
        if (low.periodStart() == null || low.periodEnd() == null
                || high.periodStart() == null || high.periodEnd() == null) {
            return Optional.empty();
        }
        if (low.amount().compareTo(high.amount()) == 0
                && low.periodStart().equals(high.periodStart())
                && low.periodEnd().equals(high.periodEnd())) {
            return Optional.of(candidate(low, high, CandidateType.EXACT,
                    DuplicateFingerprint.MATCH_REASON_EXACT));
        }
        if (low.periodStart().isBefore(high.periodEnd())
                && high.periodStart().isBefore(low.periodEnd())) {
            return Optional.of(candidate(low, high, CandidateType.OVERLAP,
                    DuplicateFingerprint.MATCH_REASON_OVERLAP));
        }
        return Optional.empty();
    }

    private static CandidateDraft candidate(ChargeFactLineageRow low, ChargeFactLineageRow high,
            CandidateType type, String matchReason) {
        var leftSignature = DuplicateFingerprint.evidenceSignature(low);
        var rightSignature = DuplicateFingerprint.evidenceSignature(high);
        return new CandidateDraft(low.organizationId(), low.id(), high.id(), type,
                DuplicateFingerprint.pairFingerprint(type, leftSignature, rightSignature),
                DuplicateFingerprint.ALGORITHM_VERSION, matchReason);
    }

    private <T> T executeWithDeadlockRetry(Supplier<T> operation) {
        for (var attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DeadlockLoserDataAccessException deadlock) {
                if (attempt >= DEADLOCK_RETRIES) {
                    throw deadlock;
                }
            }
        }
    }
}
