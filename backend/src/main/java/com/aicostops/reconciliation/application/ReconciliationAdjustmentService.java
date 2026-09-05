package com.aicostops.reconciliation.application;

import com.aicostops.allocation.application.AllocationTargetQueryService;
import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.budget.application.LedgerBudgetPort;
import com.aicostops.budget.application.LedgerBudgetPort.BudgetSelection;
import com.aicostops.budget.application.LedgerBudgetPort.EntryScopeAmount;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.domain.Budget;
import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.ledger.application.LedgerCorrectionIdempotencyStore;
import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort;
import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort.AdjustmentLineCommand;
import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort.AdjustmentPostCommand;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.CaseFullAdjustmentCommand;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.domain.ReconciliationRun;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CASE_FULL reconciliation adjustment: one reviewed append-only financial
 * action that resolves the entire current aggregate difference of one case.
 * The amount is never client-defined: it must equal the current
 * external-internal difference of a still-current reconciliation basis.
 */
@Service
public class ReconciliationAdjustmentService {

    static final String OPERATION = "RECONCILIATION_ADJUSTMENT";
    static final String CASE_RESOLVED_REASON = "RECONCILIATION_ADJUSTMENT_POSTED";
    private static final String PERMISSION_RESOLVE = "RECONCILIATION_RESOLVE";
    private static final String PERMISSION_LEDGER_CORRECT = "LEDGER_CORRECT";
    private static final int MAX_DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodFinancialWriteFence periodFence;
    private final LedgerBudgetPort budgets;
    private final AllocationTargetQueryService allocationTargets;
    private final ReconciliationExternalTruthPort externalTruth;
    private final ReconciliationInternalTruthPort internalTruth;
    private final ReconciliationTolerancePolicy tolerancePolicy;
    private final ReconciliationMatchEngine matchEngine;
    private final ReconciliationTruthHasher hasher;
    private final ReconciliationMapper mapper;
    private final HybridReconciliationMapper hybridMapper;
    private final ReconciliationAdjustmentLedgerPort adjustmentLedger;
    private final LedgerCorrectionIdempotencyStore idempotency;
    private final ReconciliationAuditPort audit;
    private final ReconciliationAdjustmentFailureInjector failureInjector;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReconciliationAdjustmentService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodFinancialWriteFence periodFence,
            LedgerBudgetPort budgets,
            AllocationTargetQueryService allocationTargets,
            ReconciliationExternalTruthPort externalTruth,
            ReconciliationInternalTruthPort internalTruth,
            ReconciliationTolerancePolicy tolerancePolicy,
            ReconciliationMatchEngine matchEngine,
            ReconciliationTruthHasher hasher,
            ReconciliationMapper mapper,
            HybridReconciliationMapper hybridMapper,
            ReconciliationAdjustmentLedgerPort adjustmentLedger,
            LedgerCorrectionIdempotencyStore idempotency,
            ReconciliationAuditPort audit,
            ReconciliationAdjustmentFailureInjector failureInjector,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.periodFence = periodFence;
        this.budgets = budgets;
        this.allocationTargets = allocationTargets;
        this.externalTruth = externalTruth;
        this.internalTruth = internalTruth;
        this.tolerancePolicy = tolerancePolicy;
        this.matchEngine = matchEngine;
        this.hasher = hasher;
        this.mapper = mapper;
        this.hybridMapper = hybridMapper;
        this.adjustmentLedger = adjustmentLedger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.failureInjector = failureInjector;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CaseFullAdjustmentResult postCaseFullAdjustment(
            AuthenticatedUser user, CaseFullAdjustmentCommand command) {
        return postCaseFullAdjustment(user, command, java.util.UUID.randomUUID().toString());
    }

    public CaseFullAdjustmentResult postCaseFullAdjustment(
            AuthenticatedUser user, CaseFullAdjustmentCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        authorization.requireOrg(context, PERMISSION_LEDGER_CORRECT);
        validateCommand(command);
        var requestHash = requestHash(context.organizationId(), context.organizationMemberId(),
                command);
        // The idempotency reservation participates in the same transaction as
        // the financial mutation, mirroring the correction service: a rollback
        // never leaves a provisional reservation behind.
        var result = withDeadlockRetry(() -> transactions.execute(status -> {
            var reservation = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), OPERATION, idempotencyKey, requestHash);
            if (reservation.replay()) {
                return replay(context.organizationId(), reservation.responseBody());
            }
            return postInTransaction(context.organizationId(), context.userId(),
                    context.organizationMemberId(), command, reservation.id());
        }));
        metrics.reconciliationAdjustment("CASE_FULL", "POSTED");
        return result;
    }

    private CaseFullAdjustmentResult postInTransaction(long organizationId, long actorUserId,
            long actorMemberId, CaseFullAdjustmentCommand command, long reservationId) {
        var caseRow = mapper.selectCaseByIdAndOrganization(organizationId, command.caseId());
        if (caseRow == null) {
            throw notFound("Reconciliation case");
        }
        var preRun = mapper.selectRunByIdAndOrganization(organizationId,
                caseRow.reconciliationRunId());
        if (preRun == null) {
            throw notFound("Reconciliation run");
        }

        // Financial lock order starts with the BillingPeriod rows, ascending id.
        var casePeriod = periodFence.lockById(organizationId, preRun.billingPeriodId());
        validateCasePeriodRules(casePeriod.status(), command.adjustmentPeriodId(),
                preRun.billingPeriodId());
        var lockedPeriodIds = command.adjustmentPeriodId() == preRun.billingPeriodId()
                ? List.of(preRun.billingPeriodId())
                : List.of(Math.min(preRun.billingPeriodId(), command.adjustmentPeriodId()),
                        Math.max(preRun.billingPeriodId(), command.adjustmentPeriodId()));
        for (var periodId : lockedPeriodIds) {
            periodFence.lockById(organizationId, periodId);
        }
        var adjustmentPeriod = periodFence.lockById(organizationId, command.adjustmentPeriodId());
        if (adjustmentPeriod.status() != BillingPeriodStatus.OPEN) {
            throw conflict("The correction period must be OPEN; current status is "
                    + adjustmentPeriod.status() + ".");
        }

        // Reconciliation identity locks, then the authoritative basis revalidation.
        var run = mapper.selectRunByIdForUpdate(organizationId, preRun.id());
        var currentCase = mapper.selectCaseByIdForUpdate(organizationId, caseRow.id());
        if (run == null || currentCase == null) {
            throw conflict("The reconciliation case changed while posting.");
        }
        if (run.status() != ReconciliationRunStatus.COMPLETED
                || !ReconciliationAlgorithm.VERSION.equals(run.algorithmVersion())
                || run.toleranceAmount().compareTo(tolerancePolicy.amount()) != 0) {
            throw staleBasis();
        }
        var currentHash = currentBasisHash(organizationId, run.billingPeriodId(),
                casePeriod.periodStart(), casePeriod.periodEnd());
        if (!currentHash.equals(run.basisHash())) {
            throw staleBasis();
        }
        if (currentCase.status() == com.aicostops.reconciliation.domain.ReconciliationCaseStatus.RESOLVED) {
            throw conflict("The reconciliation case is already resolved.");
        }

        var required = requiredAdjustment(currentCase);
        if (command.amount().compareTo(required) != 0) {
            throw conflict("The CASE_FULL amount must equal the current external-internal "
                    + "difference of " + required.toPlainString() + " " + currentCase.currency()
                    + ".");
        }

        // Explicit allocation lines only; no inferred split or remainder.
        validateAllocationTargets(organizationId, command.lines());
        var selections = budgets.resolveSelections(organizationId, adjustmentPeriod.id(),
                lineScopes(command.lines(), currentCase.currency()));
        var lockedBudgets = budgets.lockBudgets(organizationId, selections.stream()
                .map(BudgetSelection::budget)
                .filter(Objects::nonNull)
                .map(Budget::id)
                .sorted()
                .toList());
        var budgetById = lockedBudgets.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Budget::id, b -> b));
        var budgetByIndex = new java.util.HashMap<Integer, Budget>();
        selections.forEach(selection -> budgetByIndex.put(selection.entryIndex(),
                selection.budget() == null ? null : budgetById.get(selection.budget().id())));

        var now = clock.instant();
        var adjustmentKey = "ADJ:" + reservationId;
        hybridMapper.insertAdjustment(new HybridReconciliationMapper.AdjustmentInsert(
                organizationId, run.id(), currentCase.id(), adjustmentKey, "CASE_FULL",
                currentCase.providerAccountId(), currentCase.currency(), command.amount(),
                adjustmentPeriod.id(), actorMemberId, command.reasonCode(),
                command.reasonNote(), now));
        var adjustmentId = hybridMapper.lastInsertId();
        failureInjector.after("ADJUSTMENT_INSERTED");

        var lines = new ArrayList<AdjustmentLineCommand>();
        for (var line : command.lines()) {
            var scopeType = ScopeType.valueOf(line.scopeType());
            lines.add(new AdjustmentLineCommand(line.lineIndex(), line.amount(),
                    currentCase.currency(),
                    scopeType == ScopeType.PROJECT ? line.scopeId() : null,
                    scopeType == ScopeType.COST_CENTER ? line.scopeId() : null,
                    scopeType == ScopeType.TEAM ? line.scopeId() : null,
                    budgetId(budgetByIndex.get(line.lineIndex()))));
        }
        adjustmentLedger.postAdjustment(new AdjustmentPostCommand(organizationId, adjustmentId,
                adjustmentPeriod.id(), List.copyOf(lines), actorMemberId, now));
        failureInjector.after("LEDGER_ENTRY_INSERTED");

        for (var line : command.lines()) {
            var budget = budgetByIndex.get(line.lineIndex());
            if (budget != null) {
                budgets.incrementActual(organizationId, budget.id(), line.amount(), now);
            }
        }
        failureInjector.after("BUDGET_ACTUAL_MUTATED");

        audit.adjustmentPosted(organizationId, actorUserId, adjustmentId, currentCase.id(),
                run.id(), "CASE_FULL", command.amount(), currentCase.currency(),
                adjustmentPeriod.id());
        failureInjector.after("AUDIT_WRITTEN");

        if (mapper.markResolvedForAdjustment(organizationId, currentCase.id(),
                CASE_RESOLVED_REASON, command.reasonNote(), actorMemberId, now, now) != 1) {
            throw conflict("The reconciliation case changed while posting.");
        }
        failureInjector.after("CASE_RESOLVED");

        idempotency.finalize(reservationId, 200, Long.toString(adjustmentId));
        return new CaseFullAdjustmentResult(adjustmentId, currentCase.id(), run.id(),
                command.amount(), currentCase.currency());
    }

    private String currentBasisHash(long organizationId, long billingPeriodId,
            Instant periodStart, Instant periodEnd) {
        var external = externalTruth.aggregateConfirmedCharges(organizationId,
                periodStart, periodEnd);
        var internal = internalTruth.aggregateProviderLedger(organizationId, billingPeriodId);
        return hasher.hash(matchEngine.match(external, internal,
                tolerancePolicy.amount()).rows());
    }

    private static BigDecimal requiredAdjustment(ReconciliationCase caseRow) {
        var external = caseRow.externalAmount() == null ? BigDecimal.ZERO
                : caseRow.externalAmount();
        var internal = caseRow.internalAmount() == null ? BigDecimal.ZERO
                : caseRow.internalAmount();
        return ReconciliationMoney.requireScale8Exact(external.subtract(internal));
    }

    private void validateAllocationTargets(long organizationId, List<AdjustmentLine> lines) {
        for (var line : lines) {
            if (!allocationTargets.activeTargetExists(organizationId,
                    ScopeType.valueOf(line.scopeType()), line.scopeId())) {
                throw validation("Every allocation line must target an ACTIVE project, "
                        + "cost center, or team of the current organization.");
            }
        }
    }

    private static List<EntryScopeAmount> lineScopes(List<AdjustmentLine> lines,
            String currency) {
        var scopes = new ArrayList<EntryScopeAmount>();
        for (var line : lines) {
            scopes.add(new EntryScopeAmount(line.lineIndex(),
                    ScopeType.valueOf(line.scopeType()), line.scopeId(), currency));
        }
        return List.copyOf(scopes);
    }

    private static void validateCasePeriodRules(BillingPeriodStatus casePeriodStatus,
            long adjustmentPeriodId, long casePeriodId) {
        switch (casePeriodStatus) {
            case CLOSING -> throw conflict(
                    "A CLOSING billing period cannot be reconciled with a financial action.");
            case OPEN -> {
                if (adjustmentPeriodId != casePeriodId) {
                    throw conflict("An adjustment for an OPEN reconciled period must post "
                            + "into that same period.");
                }
            }
            case CLOSED -> {
                if (adjustmentPeriodId == casePeriodId) {
                    throw conflict("A CLOSED historical period is never written directly; "
                            + "select an OPEN correction period or reopen explicitly.");
                }
            }
            default -> throw conflict("Unsupported billing period state.");
        }
    }

    private static void validateCommand(CaseFullAdjustmentCommand command) {
        if (command == null || command.caseId() <= 0 || command.adjustmentPeriodId() <= 0) {
            throw validation("caseId and adjustmentPeriodId must be positive integers.");
        }
        validateMoney(command.amount(), "amount");
        if (command.reasonCode() == null || command.reasonCode().isBlank()
                || command.reasonCode().length() > 64) {
            throw validation("reasonCode must be a nonblank value of at most 64 characters.");
        }
        if (command.reasonNote() == null || command.reasonNote().isBlank()
                || command.reasonNote().length() > 2000) {
            throw validation("reasonNote must be a nonblank value of at most 2000 characters.");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw validation("At least one explicit allocation line is required; "
                    + "no target is ever inferred.");
        }
        var sum = BigDecimal.ZERO;
        var seenIndexes = new java.util.HashSet<Integer>();
        for (var line : command.lines()) {
            if (line == null || !seenIndexes.add(line.lineIndex())) {
                throw validation("Allocation lines must have unique line indexes.");
            }
            if (!"PROJECT".equals(line.scopeType()) && !"COST_CENTER".equals(line.scopeType())
                    && !"TEAM".equals(line.scopeType())) {
                throw validation("Each allocation line must target exactly one PROJECT, "
                        + "COST_CENTER or TEAM scope.");
            }
            if (line.scopeId() <= 0) {
                throw validation("Allocation line scope ids must be positive integers.");
            }
            validateMoney(line.amount(), "line amount");
            if (line.amount().signum() == 0) {
                throw validation("Allocation line amounts must be nonzero.");
            }
            sum = sum.add(line.amount());
        }
        if (ReconciliationMoney.requireScale8Exact(sum)
                .compareTo(command.amount()) != 0) {
            throw validation("Allocation lines must sum exactly to the adjustment amount; "
                    + "no remainder is inferred.");
        }
    }

    private static void validateMoney(BigDecimal value, String field) {
        if (value == null) {
            throw validation(field + " is required.");
        }
        if (value.scale() > 8 || value.precision() - value.scale() > 12) {
            throw validation(field + " must fit DECIMAL(20,8).");
        }
    }

    private CaseFullAdjustmentResult replay(long organizationId, String responseBody) {
        long adjustmentId;
        try {
            var value = responseBody == null ? "" : responseBody.trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            adjustmentId = Long.parseLong(value);
        } catch (RuntimeException invalidStoredResponse) {
            throw new IllegalStateException("Stored adjustment idempotency response is invalid",
                    invalidStoredResponse);
        }
        var adjustment = hybridMapper.selectAdjustmentByIdAndOrganization(organizationId,
                adjustmentId);
        if (adjustment == null) {
            throw new IllegalStateException("A committed adjustment must be readable");
        }
        return new CaseFullAdjustmentResult(adjustment.id(), adjustment.reconciliationCaseId(),
                adjustment.reconciliationRunId(), adjustment.amount(), adjustment.currency());
    }

    private String requestHash(long organizationId, long actorMemberId,
            CaseFullAdjustmentCommand command) {
        var canonical = new StringBuilder()
                .append("operation=").append(OPERATION)
                .append("\norgId=").append(organizationId)
                .append("\nactorMemberId=").append(actorMemberId)
                .append("\ncaseId=").append(command.caseId())
                .append("\namount=").append(command.amount().toPlainString())
                .append("\nadjustmentPeriodId=").append(command.adjustmentPeriodId())
                .append("\nreasonCode=").append(command.reasonCode())
                .append("\nreasonNote=").append(command.reasonNote());
        for (var line : command.lines()) {
            canonical.append("\nline=").append(line.lineIndex())
                    .append(':').append(line.scopeType())
                    .append(':').append(line.scopeId())
                    .append(':').append(line.amount().toPlainString());
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }

    private static Long budgetId(Budget budget) {
        return budget == null ? null : budget.id();
    }

    private static DomainException staleBasis() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Reconciliation basis changed",
                "STALE_BASIS: the reconciliation run no longer matches current financial "
                        + "truth; run a new reconciliation before posting the adjustment.");
    }

    private static DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Reconciliation adjustment conflict", detail);
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid reconciliation adjustment", detail);
    }

    private static DomainException notFound(String type) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", type + " is not available in the current organization.");
    }

    private <T> T withDeadlockRetry(Supplier<T> operation) {
        for (var attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DeadlockLoserDataAccessException | CannotSerializeTransactionException retryable) {
                if (attempt >= MAX_DEADLOCK_RETRIES) {
                    throw retryable;
                }
            }
        }
    }

    public record CaseFullAdjustmentCommand(
            long caseId,
            BigDecimal amount,
            long adjustmentPeriodId,
            List<AdjustmentLine> lines,
            String reasonCode,
            String reasonNote) {
    }

    public record AdjustmentLine(
            int lineIndex,
            String scopeType,
            long scopeId,
            BigDecimal amount) {
    }

    public record CaseFullAdjustmentResult(
            long adjustmentId,
            Long caseId,
            long runId,
            BigDecimal amount,
            String currency) {
    }
}
