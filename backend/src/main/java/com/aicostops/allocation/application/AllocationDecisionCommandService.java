package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand;
import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
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
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual allocation draft creation, replace-lines editing, and confirm.
 *
 * <p>Every operation routes through the {@link AllocationSubjectPort} of the
 * decision's subject: CHARGE_FACT keeps the confirmed-import lineage and CLEAN
 * review gates, EXPENSE_CLAIM requires an APPROVED claim. Lock order is always
 * {@code source -> allocation_decision -> allocation_line}, with related DRAFT
 * decisions of the same subject locked by decision id ascending; the two
 * subjects touch disjoint tables, so charge and expense workflows cannot
 * cross-deadlock.
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
    private final Map<AllocationSubjectType, AllocationSubjectPort> subjectPorts;
    private final AllocationIdempotency idempotency;
    private final AllocationAuditPort audit;
    private final AllocationResponseCodec codec;
    private final TransactionTemplate transactions;

    public AllocationDecisionCommandService(
            AuthorizationContextService authorizationContexts,
            AllocationDecisionRepository decisions,
            AllocationRuleRepository rules,
            AllocationTargetDirectory targets,
            List<AllocationSubjectPort> subjectPorts,
            AllocationIdempotency idempotency,
            AllocationAuditPort audit,
            AllocationResponseCodec codec,
            PlatformTransactionManager transactionManager) {
        this.authorizationContexts = authorizationContexts;
        this.decisions = decisions;
        this.rules = rules;
        this.targets = targets;
        this.subjectPorts = subjectPorts.stream().collect(Collectors.toUnmodifiableMap(
                AllocationSubjectPort::subjectType, Function.identity()));
        this.idempotency = idempotency;
        this.audit = audit;
        this.codec = codec;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // -- manual draft ----------------------------------------------------------

    /** Charge endpoint: creates a manual draft for a CHARGE_FACT subject. */
    public AllocationDecisionView createManualDraft(AuthenticatedUser user, long chargeFactId,
            ManualDraftCommand command, String idempotencyKey) {
        return createManualDraft(user, AllocationSubjectType.CHARGE_FACT, chargeFactId,
                command, idempotencyKey);
    }

    public AllocationDecisionView createManualDraft(AuthenticatedUser user,
            AllocationSubjectType subjectType, long subjectId, ManualDraftCommand command,
            String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_EDIT);
        AllocationIdempotency.validateKey(idempotencyKey);
        var subject = requireSubject(subjectType);
        var requestHash = idempotency.manualDraftRequestHash(context.organizationId(),
                context.organizationMemberId(), subjectType, subjectId, command.lines());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var reserved = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), AllocationIdempotency.OPERATION_MANUAL_DRAFT,
                    idempotencyKey, requestHash);
            if (reserved.replay()) {
                return codec.decisionFromJson(reserved.responseBody());
            }
            var load = subject.loadForUpdate(context.organizationId(), subjectId);
            if (subjectType == AllocationSubjectType.EXPENSE_CLAIM) {
                // Expenses are only allocatable after approval (M4 eligibility).
                subject.assertConfirmEligible(context.organizationId(), load);
            }
            assertNoConfirmed(context.organizationId(), subjectType, subjectId);
            var drafts = findDraftDecisionsForUpdate(context.organizationId(), subjectType, subjectId);
            if (drafts.stream().anyMatch(draft -> draft.decisionSource() == AllocationDecisionSource.MANUAL)) {
                throw manualDraftExists();
            }
            validateLinesAgainstSubject(context.organizationId(), command.lines(), load.currency());
            for (var draft : drafts) {
                // Rule drafts are superseded with their lines preserved; that is
                // the manual-override lineage.
                decisions.supersedeDecision(context.organizationId(), draft.id());
            }
            var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                    context.organizationId(), subjectType,
                    subjectType == AllocationSubjectType.CHARGE_FACT ? subjectId : null,
                    subjectType == AllocationSubjectType.EXPENSE_CLAIM ? subjectId : null,
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
            var subject = requireSubject(pre.subjectType());
            var load = subject.loadForUpdate(context.organizationId(), subjectIdOf(pre));
            var locked = decisions.findByIdForUpdate(context.organizationId(), decisionId)
                    .orElseThrow(this::decisionNotFound);
            if (locked.decisionSource() != AllocationDecisionSource.MANUAL
                    || locked.status() != AllocationDecisionStatus.DRAFT) {
                throw decisionNotDraft(
                        "Only MANUAL DRAFT decisions can be edited; override a RULE draft "
                                + "by creating a new manual draft.");
            }
            validateLinesAgainstSubject(context.organizationId(), lines, load.currency());
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
            var subject = requireSubject(pre.subjectType());
            var subjectId = subjectIdOf(pre);
            var locked = decisions.findByIdForUpdate(context.organizationId(), decisionId)
                    .orElseThrow(this::decisionNotFound);
            if (locked.status() != AllocationDecisionStatus.DRAFT) {
                throw decisionNotDraft("Only DRAFT decisions can be confirmed.");
            }
            var load = subject.loadForUpdate(context.organizationId(), subjectId);
            subject.assertConfirmEligible(context.organizationId(), load);
            var lines = decisions.linesOfDecisionForUpdate(context.organizationId(), decisionId);
            if (lines.isEmpty()) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Allocation decision has no lines",
                        "A confirmed allocation decision must carry at least one line.");
            }
            for (var line : lines) {
                if (!line.currency().equals(load.currency())) {
                    throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                            "Allocation line currency mismatch",
                            "Every line currency must match the subject currency.");
                }
                requireActiveTarget(context.organizationId(), line);
            }
            var sum = lines.stream()
                    .map(AllocationLine::allocatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(load.amount()) != 0) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_SUM_MISMATCH,
                        "Allocation sum mismatch",
                        "The lines must exactly sum to the subject amount.");
            }
            assertNoConfirmed(context.organizationId(), pre.subjectType(), subjectId);
            decisions.confirmDecision(context.organizationId(), decisionId);
            subject.setCurrentDecisionPointer(context.organizationId(), subjectId, decisionId);
            audit.decisionConfirmed(context.organizationId(), context.userId(), decisionId,
                    pre.subjectType(), subjectId, locked.decisionSource(),
                    locked.allocationRuleId(), lines.size(), load.currency());
            var view = buildView(context.organizationId(), decisionId);
            idempotency.finalize(reserved.id(), 200, codec.decisionToJson(view));
            return view;
        }));
    }

    // -- shared helpers --------------------------------------------------------

    private AllocationSubjectPort requireSubject(AllocationSubjectType subjectType) {
        var subject = subjectPorts.get(subjectType);
        if (subject == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Unsupported allocation subject",
                    "No allocation subject adapter is registered for " + subjectType + ".");
        }
        return subject;
    }

    private static Long subjectIdOf(AllocationDecision decision) {
        return decision.subjectType() == AllocationSubjectType.CHARGE_FACT
                ? decision.chargeFactId()
                : decision.expenseClaimId();
    }

    private void assertNoConfirmed(long organizationId, AllocationSubjectType subjectType,
            long subjectId) {
        var confirmed = subjectType == AllocationSubjectType.CHARGE_FACT
                ? decisions.countConfirmedForCharge(organizationId, subjectId)
                : decisions.countConfirmedForExpense(organizationId, subjectId);
        if (confirmed > 0) {
            throw alreadyConfirmed();
        }
    }

    private List<AllocationDecision> findDraftDecisionsForUpdate(long organizationId,
            AllocationSubjectType subjectType, long subjectId) {
        return subjectType == AllocationSubjectType.CHARGE_FACT
                ? decisions.findDraftDecisionsByChargeForUpdate(organizationId, subjectId)
                : decisions.findDraftDecisionsByExpenseForUpdate(organizationId, subjectId);
    }

    private void validateLinesAgainstSubject(long organizationId,
            List<AllocationLineCommand> lines, String subjectCurrency) {
        for (var line : lines) {
            if (!line.currency().equals(subjectCurrency)) {
                throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                        "Invalid allocation line",
                        "Every line currency must match the subject currency " + subjectCurrency + ".");
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

    private DomainException decisionNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Allocation decision not found",
                "The allocation decision is not available in the current organization.");
    }

    private static DomainException manualDraftExists() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.MANUAL_ALLOCATION_DRAFT_EXISTS,
                "Manual allocation draft exists",
                "The subject already has a MANUAL DRAFT allocation; edit it instead "
                        + "of creating another one.");
    }

    private static DomainException alreadyConfirmed() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_ALREADY_CONFIRMED,
                "Allocation already confirmed",
                "The subject already has a confirmed allocation that cannot be rewritten.");
    }

    private static DomainException decisionNotDraft(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.DECISION_NOT_DRAFT,
                "Allocation decision is not editable",
                detail);
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Subject not eligible for allocation",
                detail);
    }
}