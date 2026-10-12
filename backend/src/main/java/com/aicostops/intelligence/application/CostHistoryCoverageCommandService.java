package com.aicostops.intelligence.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.domain.CostHistoryCoverage.Scope;
import com.aicostops.intelligence.domain.CostHistoryCoverage.Status;
import com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** A privileged declaration, never automatic proof inferred from an import's date range. */
@Service
public class CostHistoryCoverageCommandService {
    private final AuthorizationContextService contexts;
    private final CostHistoryCoverageMapper store;
    private final AuditService audit;
    private final Clock clock;
    private final CostIntelligenceAnalysisService analyses;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public CostHistoryCoverageCommandService(AuthorizationContextService contexts,
            CostHistoryCoverageMapper store, AuditService audit, Clock clock, CostIntelligenceAnalysisService analyses) {
        this.contexts = contexts;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.analyses = analyses;
    }

    public record ReviewRequest(String currency, Scope scope, long scopeId, LocalDate postingDay,
            Status status, String reviewNote, Long expectedRevision) {}
    public record ReviewResult(long revision, CostHistoryCoverageMapper.CoverageRow review) {}

    public AnalysisRunOutcome refreshAnalysis(AuthenticatedUser user, String currency) {
        var context = contexts.fresh(user);
        authorization.requireOrg(context, "COST_COVERAGE_MANAGE");
        authorization.requireOrg(context, "COST_READ");
        var date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
        return analyses.runAnalysis(context.organizationId(), date,
                CostIntelligenceValidation.validatedCurrency(currency));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewResult review(AuthenticatedUser user, ReviewRequest request) {
        var context = contexts.fresh(user);
        authorization.requireOrg(context, "COST_COVERAGE_MANAGE");
        if (request == null || request.scope() == null || request.status() == null
                || request.scopeId() <= 0 || request.postingDay() == null || request.expectedRevision() == null
                || request.expectedRevision() < 0
                || request.reviewNote() == null || request.reviewNote().isBlank()
                || request.reviewNote().strip().length() > 500
                || !request.postingDay().isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid coverage review", "Review a past UTC posting day with an exact scope and a nonblank basis note of at most 500 characters.");
        }
        var org = context.organizationId();
        var currency = CostIntelligenceValidation.validatedCurrency(request.currency());
        var scope = request.scope().name();
        if (store.ownedScope(org, scope, request.scopeId()) != 1) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Scope not found", "The coverage scope does not belong to this organization.");
        }
        store.ensureRevision(org, currency);
        var revision = store.lockRevision(org, currency);
        if (revision != request.expectedRevision()) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Coverage changed", "Refresh coverage before recording another review.");
        }
        var previous = store.find(org, currency, scope, request.scopeId(), request.postingDay());
        if (request.status() == Status.REVOKED && previous == null) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "No coverage to revoke", "This posting day has no review declaration.");
        }
        var note = request.reviewNote().strip();
        var stamp = store.ledgerStamp(org, currency, scope, request.scopeId(), request.postingDay(),
                request.postingDay().plusDays(1));
        if (previous != null && previous.status().equals(request.status().name())
                && previous.reviewedByUserId() == context.userId()
                && previous.reviewNote().equals(note) && previous.ledgerEntryCount() == stamp.entryCount()
                && previous.ledgerMaxEntryId() == stamp.maximumEntryId()) {
            return new ReviewResult(revision, previous);
        }
        store.save(org, currency, scope, request.scopeId(), request.postingDay(), request.status().name(),
                note, stamp, context.userId(), clock.instant());
        if (store.advanceRevision(org, currency) != 1) throw new IllegalStateException("Coverage revision lost");
        var saved = store.find(org, currency, scope, request.scopeId(), request.postingDay());
        audit.append("COST_HISTORY_COVERAGE_REVIEWED", org, context.userId(), "COST_HISTORY_COVERAGE",
                saved.id(), Map.of("currency", currency, "scopeType", scope, "scopeId", request.scopeId(),
                        "postingDay", request.postingDay().toString(), "status", request.status().name(),
                        "basis", "MANUAL_LEDGER_REVIEW", "revision", revision + 1,
                        "reviewNote", note, "ledgerEntryCount", stamp.entryCount(),
                        "ledgerMaxEntryId", stamp.maximumEntryId()));
        return new ReviewResult(revision + 1, saved);
    }
}
