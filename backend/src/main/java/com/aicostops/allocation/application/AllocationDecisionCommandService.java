package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand;
import com.aicostops.allocation.application.AllocationReadModels.AllocationChargeRow;
import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper;
import com.aicostops.attribution.application.AllocationDecisionRepository;
import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.application.AllocationTargetDirectory;
import com.aicostops.attribution.application.NewAllocationDecisionDraft;
import com.aicostops.attribution.application.NewAllocationLine;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationDecisionStatus;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual allocation draft creation, replace-lines editing, and confirm.
 *
 * <p>Lock order is always {@code charge_fact -> allocation_decision ->
 * allocation_line}, with related DRAFT decisions of the same charge locked by
 * decision id ascending; this matches the duplicate-review workflow's
 * charge-first ordering so the two workflows cannot deadlock.
 */
@Service
public class AllocationDecisionCommandService {

    private static final String PERMISSION_ALLOCATION_EDIT = "ALLOCATION_EDIT";
    private static final String PERMISSION_ALLOCATION_CONFIRM = "ALLOCATION_CONFIRM";
    private static final int DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationDecisionRepository decisions;
    private final AllocationRuleRepository rules;
    private final AllocationTargetDirectory targets;
    private final AllocationChargeFactMapper charges;
    private final AllocationIdempotency idempotency;
    private final AllocationAuditPort audit;
    private final AllocationResponseCodec codec;
    private final TransactionTemplate transactions;

    public AllocationDecisionCommandService(
            AuthorizationContextService authorizationContexts,
            AllocationDecisionRepository decisions,
            AllocationRuleRepository rules,
            AllocationTargetDirectory targets,
            AllocationChargeFactMapper charges,
            AllocationIdempotency idempotency,
            AllocationAuditPort audit,
            AllocationResponseCodec codec,
            PlatformTransactionManager transactionManager) {
        this.authorizationContexts = authorizationContexts;
        this.decisions = decisions;
        this.rules = rules;
        this.targets = targets;
        this.charges = charges;
        this.idempotency = idempotency;
        this.audit = audit;
        this.codec = codec;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // -- manual draft ----------------------------------------------------------

    public AllocationDecisionView createManualDraft(AuthenticatedUser user, long chargeFactId,
            ManualDraftCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_EDIT);
        AllocationIdempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.manualDraftRequestHash(context.organizationId(),
                context.organizationMemberId(), chargeFactId, command.lines());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var reserved = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), AllocationIdempotency.OPERATION_MANUAL_DRAFT,
                    idempotencyKey, requestHash);
            if (reserved.replay()) {
                return codec.decisionFromJson(reserved.responseBody());
            }
            var charge = requireChargeForUpdate(context.organizationId(), chargeFactId);
            if (decisions.countConfirmedForCharge(context.organizationId(), chargeFactId) > 0) {
                throw alreadyConfirmed();
            }
            var drafts = decisions.findDraftDecisionsByChargeForUpdate(
                    context.organizationId(), chargeFactId);
            if (drafts.stream().anyMatch(draft -> draft.decisionSource() == AllocationDecisionSource.MANUAL)) {
                throw manualDraftExists();
            }
            validateLinesAgainstCharge(context.organizationId(), command.lines(), charge.currency());
            for (var draft : drafts) {
                // Rule drafts are superseded with their lines preserved; that is
                // the manual-override lineage.
                decisions.supersedeDecision(context.organizationId(), draft.id());
            }
            var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                    context.organizationId(), AllocationSubjectType.CHARGE_FACT, chargeFactId, null,
                    AllocationDecisionSource.MANUAL, null, context.organizationMemberId()));
            insertLines(context.organizationId(), decisionId, command.lines());
            var view = buildView(context.organizationId(), decisionId);
            idempotency.finalize(reserved.id(), 200, codec.decisionToJson(view));
            return view;
        }));
    }

    // -- replace lines ---------------------------------------------------------

    public AllocationDecisionView replaceLines(AuthenticatedUser user, long decisionId,
            List<AllocationLineCommand> lines) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_EDIT);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var pre = decisions.findByIdAndOrganization(context.organizationId(), decisionId)
                    .orElseThrow(this::decisionNotFound);
            var charge = requireChargeForUpdate(context.organizationId(), pre.chargeFactId());
            var locked = decisions.findByIdForUpdate(context.organizationId(), decisionId)
                    .orElseThrow(this::decisionNotFound);
            if (locked.decisionSource() != AllocationDecisionSource.MANUAL
                    || locked.status() != AllocationDecisionStatus.DRAFT) {
                throw decisionNotDraft(
                        "Only MANUAL DRAFT decisions can be edited; override a RULE draft "
                                + "by creating a new manual draft.");
            }
            validateLinesAgainstCharge(context.organizationId(), lines, charge.currency());
            // Lock the existing lines, then replace the whole set.
            decisions.linesOfDecisionForUpdate(context.organizationId(), decisionId);
            decisions.deleteLinesOfDecision(context.organizationId(), decisionId);
            insertLines(context.organizationId(), decisionId, lines);
            return buildView(context.organizationId(), decisionId);
        }));
    }

    // -- confirm ---------------------------------------------------------------

    public AllocationDecisionView confirm(AuthenticatedUser user, long decisionId,
            String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_CONFIRM);
        AllocationIdempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.confirmRequestHash(context.organizationId(),
                context.organizationMemberId(), decisionId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var reserved = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), AllocationIdempotency.OPERATION_CONFIRM,
                    idempotencyKey, requestHash);
            if (reserved.replay()) {
                return codec.decisionFromJson(reserved.responseBody());
            }
            var pre = decisions.findByIdAndOrganization(context.organizationId(), decisionId)
                    .orElseThrow(this::decisionNotFound);
            var charge = requireChargeForUpdate(context.organizationId(), pre.chargeFactId());
            var locked = decisions.findByIdForUpdate(context.organizationId(), decisionId)
                    .orElseThrow(this::decisionNotFound);
            if (locked.status() != AllocationDecisionStatus.DRAFT) {
                throw decisionNotDraft("Only DRAFT decisions can be confirmed.");
            }
            var lineage = charges.selectLineage(context.organizationId(), charge.id());
            if (lineage == null || !lineage.confirmedImport()) {
                throw notEligible(
                        "The charge does not belong to the confirmed import lineage.");
            }
            if (charge.reviewStatus() != ReviewStatus.CLEAN) {
                throw notEligible(
                        "Only CLEAN charges are eligible for allocation confirm.");
            }
            if (charge.currentAllocationDecisionId() != null) {
                throw alreadyConfirmed();
            }
            var lines = decisions.linesOfDecisionForUpdate(context.organizationId(), decisionId);
            if (lines.isEmpty()) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Allocation decision has no lines",
                        "A confirmed allocation decision must carry at least one line.");
            }
            for (var line : lines) {
                if (!line.currency().equals(charge.currency())) {
                    throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                            "Allocation line currency mismatch",
                            "Every line currency must match the charge currency.");
                }
                requireActiveTarget(context.organizationId(), line);
            }
            var sum = lines.stream()
                    .map(AllocationLine::allocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(charge.amount()) != 0) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_SUM_MISMATCH,
                        "Allocation sum mismatch",
                        "The lines must exactly sum to the charge amount.");
            }
            if (decisions.countConfirmedForCharge(context.organizationId(), charge.id()) > 0) {
                throw alreadyConfirmed();
            }
            decisions.confirmDecision(context.organizationId(), decisionId);
            if (charges.updateCurrentDecisionPointer(
                    context.organizationId(), charge.id(), decisionId) != 1) {
                throw new IllegalStateException(
                        "The charge current-decision pointer update must affect exactly one row");
            }
            audit.decisionConfirmed(context.organizationId(), context.userId(), decisionId,
                    charge.id(), locked.decisionSource(), locked.allocationRuleId(),
                    lines.size(), charge.currency());
            var view = buildView(context.organizationId(), decisionId);
            idempotency.finalize(reserved.id(), 200, codec.decisionToJson(view));
            return view;
        }));
    }

    // -- shared helpers --------------------------------------------------------

    private AllocationChargeRow requireChargeForUpdate(long organizationId, long chargeFactId) {
        return Optional.ofNullable(charges.selectChargeForUpdate(organizationId, chargeFactId))
                .orElseThrow(this::chargeNotFound);
    }

    private void validateLinesAgainstCharge(long organizationId,
            List<AllocationLineCommand> lines, String chargeCurrency) {
        for (var line : lines) {
            if (!line.currency().equals(chargeCurrency)) {
                throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                        "Invalid allocation line",
                        "Every line currency must match the charge currency " + chargeCurrency + ".");
            }
            if (!activeTargetExists(organizationId, line)) {
                throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                        "Invalid allocation target",
                        "Every line target must be an ACTIVE project, cost center, or team "
                                + "of the current organization.");
            }
        }
    }

    private void requireActiveTarget(long organizationId, AllocationLine line) {
        var valid = line.projectId() != null
                ? targets.activeProjectExists(organizationId, line.projectId())
                : line.costCenterId() != null
                        ? targets.activeCostCenterExists(organizationId, line.costCenterId())
                        : targets.activeTeamExists(organizationId, line.teamId());
        if (!valid) {
            throw notEligible(
                    "Every line target must be an ACTIVE project, cost center, or team "
                            + "of the current organization.");
        }
    }

    private boolean activeTargetExists(long organizationId, AllocationLineCommand line) {
        if (line.projectId() != null) {
            return targets.activeProjectExists(organizationId, line.projectId());
        }
        if (line.costCenterId() != null) {
            return targets.activeCostCenterExists(organizationId, line.costCenterId());
        }
        return targets.activeTeamExists(organizationId, line.teamId());
    }

    private void insertLines(long organizationId, long decisionId,
            List<AllocationLineCommand> lines) {
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            decisions.insertLine(new NewAllocationLine(
                    organizationId, decisionId, index, line.allocatedAmount(), line.currency(),
                    line.projectId(), line.costCenterId(), line.teamId()));
        }
    }

    private AllocationDecisionView buildView(long organizationId, long decisionId) {
        var decision = decisions.findByIdAndOrganization(organizationId, decisionId)
                .orElseThrow(() -> new IllegalStateException(
                        "A just-written allocation decision must be readable"));
        return viewOf(organizationId, decision);
    }

    private AllocationDecisionView viewOf(long organizationId, AllocationDecision decision) {
        var lines = decisions.linesOfDecision(organizationId, decision.id());
        AllocationRuleTrace trace = null;
        if (decision.allocationRuleId() != null) {
            trace = rules.findByIdAndOrganization(organizationId, decision.allocationRuleId())
                    .map(rule -> new AllocationRuleTrace(
                            rule.id(), rule.ruleKey(), rule.version(), rule.priority()))
                    .orElse(null);
        }
        return new AllocationDecisionView(decision, lines, trace);
    }

    private <T> T executeWithDeadlockRetry(java.util.function.Supplier<T> operation) {
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

    private DomainException chargeNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Charge not found",
                "The charge is not available in the current organization.");
    }

    private DomainException decisionNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Allocation decision not found",
                "The allocation decision is not available in the current organization.");
    }

    private static DomainException manualDraftExists() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.MANUAL_ALLOCATION_DRAFT_EXISTS,
                "Manual allocation draft exists",
                "The charge already has a MANUAL DRAFT allocation; edit it instead "
                        + "of creating another one.");
    }

    private static DomainException alreadyConfirmed() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_ALREADY_CONFIRMED,
                "Allocation already confirmed",
                "The charge already has a confirmed allocation that cannot be rewritten.");
    }

    private static DomainException decisionNotDraft(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.DECISION_NOT_DRAFT,
                "Allocation decision is not editable",
                detail);
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Charge not eligible for allocation",
                detail);
    }
}
