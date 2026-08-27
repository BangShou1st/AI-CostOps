package com.aicostops.ledger.application;

import com.aicostops.allocation.application.AllocationTargetQueryService;
import com.aicostops.budget.application.LedgerBudgetPort;
import com.aicostops.budget.application.LedgerBudgetPort.BudgetSelection;
import com.aicostops.budget.application.LedgerBudgetPort.EntryScopeAmount;
import com.aicostops.budget.domain.Budget;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand.Replacement;
import com.aicostops.ledger.application.LedgerReadModels.CorrectionResult;
import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingDetail;
import com.aicostops.ledger.domain.CorrectionMode;
import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerEntryType;
import com.aicostops.ledger.domain.LedgerSourceType;
import com.aicostops.ledger.infrastructure.LedgerPostingMapper;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ACID, append-only correction orchestration for one historical LedgerEntry. */
@Service
public class LedgerCorrectionService {

    private static final String PERMISSION_LEDGER_CORRECT = "LEDGER_CORRECT";
    private static final int MAX_DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final LedgerBudgetPort budgets;
    private final AllocationTargetQueryService allocationTargets;
    private final LedgerPostingMapper ledger;
    private final LedgerAuditPort audit;
    private final LedgerCorrectionIdempotency idempotency;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public LedgerCorrectionService(
            AuthorizationContextService authorizationContexts,
            LedgerBudgetPort budgets,
            AllocationTargetQueryService allocationTargets,
            LedgerPostingMapper ledger,
            LedgerAuditPort audit,
            LedgerCorrectionIdempotency idempotency,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.budgets = budgets;
        this.allocationTargets = allocationTargets;
        this.ledger = ledger;
        this.audit = audit;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CorrectionResult correct(AuthenticatedUser user, CorrectionCommand command,
            String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_LEDGER_CORRECT);
        LedgerCorrectionIdempotency.validateKey(idempotencyKey);
        validateCommand(command);
        var requestHash = idempotency.requestHash(context.organizationId(),
                context.organizationMemberId(), command);
        var result = withDeadlockRetry(() -> transactions.execute(status -> correctInTransaction(
                context.organizationId(), context.userId(), context.organizationMemberId(),
                command, idempotencyKey, requestHash)));
        metrics.correction(command.mode().name(), "POSTED");
        return result;
    }

    private CorrectionResult correctInTransaction(long organizationId, long actorUserId,
            long actorMemberId, CorrectionCommand command, String idempotencyKey,
            String requestHash) {
        var reservation = idempotency.reserve(organizationId, actorMemberId, idempotencyKey,
                requestHash);
        if (reservation.replay()) {
            return replay(organizationId, reservation.responseBody());
        }

        // This identity read intentionally happens before period/budget locks. It
        // returns the same privacy-preserving 404 for absent or foreign entries.
        var historicalTarget = ledger.selectEntryByIdAndOrganization(organizationId,
                command.targetEntryId());
        if (historicalTarget == null) {
            throw notFound("Ledger entry");
        }
        if (command.replacement() != null
                && !historicalTarget.currency().equals(command.replacement().currency())) {
            throw validation("Replacement currency must match the historical entry currency.");
        }
        var historicalPosting = ledger.selectPostingByIdAndOrganization(organizationId,
                historicalTarget.postingId());
        if (historicalPosting == null) {
            throw notFound("Ledger posting");
        }

        var correctionPeriod = budgets.lockOpenPeriod(organizationId, command.correctionPeriodId());
        var entryScopes = correctionScopes(historicalTarget, command.replacement());
        var selections = budgets.resolveSelections(organizationId, correctionPeriod.id(), entryScopes);
        var lockedBudgets = budgets.lockBudgets(organizationId, selections.stream()
                .map(BudgetSelection::budget)
                .filter(Objects::nonNull)
                .map(Budget::id)
                .toList());
        var budgetById = lockedBudgets.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Budget::id, budget -> budget));
        var budgetByIndex = new HashMap<Integer, Budget>();
        selections.forEach(selection -> budgetByIndex.put(selection.entryIndex(),
                selection.budget() == null ? null : budgetById.get(selection.budget().id())));

        // Frozen correction lock order: period -> budgets -> target entry -> parent posting.
        var target = ledger.selectEntryByIdForUpdate(organizationId, command.targetEntryId());
        var targetPosting = target == null ? null
                : ledger.selectPostingByIdForUpdate(organizationId, target.postingId());
        if (target == null || targetPosting == null || target.postingId() != historicalTarget.postingId()
                || targetPosting.id() != historicalPosting.id()) {
            throw stateConflict("The target Ledger entry changed while correcting.");
        }
        var existingCorrection = ledger.selectCorrectionGroupByTargetForUpdate(organizationId,
                target.id());
        if (existingCorrection != null) {
            throw stateConflict("The target Ledger entry has already been reversed.");
        }
        if (command.replacement() != null) {
            validateReplacementTarget(organizationId, command.replacement());
        }

        var now = clock.instant();
        var correctionKey = "CORRECTION_COMMAND:" + reservation.id();
        ledger.insertCorrectionGroup(organizationId, correctionKey, command.reasonCode(),
                command.reasonText(), target.id(), targetPosting.id(), "POSTED", actorMemberId, now);
        var correctionGroupId = ledger.lastCorrectionGroupId();
        ledger.insertPosting(organizationId, "CORRECTION:" + correctionGroupId,
                LedgerSourceType.CORRECTION.name(), target.id(), targetPosting.allocationDecisionId(),
                correctionPeriod.id(), "POSTED", actorMemberId, now, now);
        var postingId = ledger.lastInsertId();

        var reversalAmount = target.amount().negate();
        ledger.insertEntry(organizationId, postingId, 0, LedgerEntryType.REVERSAL.name(),
                reversalAmount, target.currency(), target.projectId(), target.costCenterId(),
                target.teamId(), budgetId(budgetByIndex.get(0)), target.sourceChargeFactId(),
                target.sourceExpenseClaimId(), target.allocationLineId(), correctionGroupId,
                target.id(), now);
        incrementActual(organizationId, budgetByIndex.get(0), reversalAmount, now);

        var entryCount = 1;
        if (command.mode() == CorrectionMode.REPLACE) {
            var replacement = command.replacement();
            var replacementAmount = replacement.amount();
            ledger.insertEntry(organizationId, postingId, 1, LedgerEntryType.ADJUSTMENT.name(),
                    replacementAmount, replacement.currency(), replacement.projectId(),
                    replacement.costCenterId(), replacement.teamId(), budgetId(budgetByIndex.get(1)),
                    target.sourceChargeFactId(), target.sourceExpenseClaimId(), null,
                    correctionGroupId, null, now);
            incrementActual(organizationId, budgetByIndex.get(1), replacementAmount, now);
            entryCount++;
        }

        audit.correctionPosted(organizationId, actorUserId, postingId, correctionGroupId, target.id(),
                command.mode().name(), entryCount, target.currency());
        idempotency.finalize(reservation.id(), 200, Long.toString(correctionGroupId));
        return result(organizationId, correctionGroupId);
    }

    private CorrectionResult replay(long organizationId, String responseBody) {
        long correctionGroupId;
        try {
            var value = responseBody == null ? "" : responseBody.trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            correctionGroupId = Long.parseLong(value);
        } catch (RuntimeException invalidStoredResponse) {
            throw new IllegalStateException("Stored correction idempotency response is invalid",
                    invalidStoredResponse);
        }
        return result(organizationId, correctionGroupId);
    }

    private CorrectionResult result(long organizationId, long correctionGroupId) {
        var group = ledger.selectCorrectionGroupByIdAndOrganization(organizationId, correctionGroupId);
        if (group == null) {
            throw new IllegalStateException("A committed correction group must be readable");
        }
        var posting = ledger.selectPostingByKey(organizationId, "CORRECTION:" + correctionGroupId);
        if (posting == null) {
            throw new IllegalStateException("A committed correction posting must be readable");
        }
        return new CorrectionResult(group, new LedgerPostingDetail(posting,
                ledger.selectEntriesByPostingId(organizationId, posting.id())));
    }

    private static List<EntryScopeAmount> correctionScopes(LedgerEntry target,
            Replacement replacement) {
        var scopes = new java.util.ArrayList<EntryScopeAmount>();
        scopes.add(scope(0, target.projectId(), target.costCenterId(), target.teamId(), target.currency()));
        if (replacement != null) {
            scopes.add(scope(1, replacement.projectId(), replacement.costCenterId(), replacement.teamId(),
                    replacement.currency()));
        }
        return List.copyOf(scopes);
    }

    private static EntryScopeAmount scope(int index, Long projectId, Long costCenterId, Long teamId,
            String currency) {
        var count = (projectId == null ? 0 : 1) + (costCenterId == null ? 0 : 1)
                + (teamId == null ? 0 : 1);
        if (count != 1) {
            throw validation("A correction entry must have exactly one target.");
        }
        if (projectId != null) {
            return new EntryScopeAmount(index, ScopeType.PROJECT, projectId, currency);
        }
        if (costCenterId != null) {
            return new EntryScopeAmount(index, ScopeType.COST_CENTER, costCenterId, currency);
        }
        return new EntryScopeAmount(index, ScopeType.TEAM, teamId, currency);
    }

    private void incrementActual(long organizationId, Budget budget, BigDecimal amount, Instant now) {
        if (budget != null) {
            budgets.incrementActual(organizationId, budget.id(), amount, now);
        }
    }

    private void validateReplacementTarget(long organizationId, Replacement replacement) {
        var targetType = replacement.projectId() != null ? ScopeType.PROJECT
                : replacement.costCenterId() != null ? ScopeType.COST_CENTER : ScopeType.TEAM;
        var targetId = replacement.projectId() != null ? replacement.projectId()
                : replacement.costCenterId() != null ? replacement.costCenterId() : replacement.teamId();
        if (!allocationTargets.activeTargetExists(organizationId, targetType, targetId)) {
            throw validation("The replacement target must be an ACTIVE project, cost center, or team "
                    + "of the current organization.");
        }
    }

    private static Long budgetId(Budget budget) {
        return budget == null ? null : budget.id();
    }

    private static void validateCommand(CorrectionCommand command) {
        if (command == null || command.targetEntryId() <= 0 || command.correctionPeriodId() <= 0) {
            throw validation("targetEntryId and correctionPeriodId must be positive integers.");
        }
        if (command.mode() == null) {
            throw validation("mode is required.");
        }
        if (command.reasonCode() == null || command.reasonCode().isBlank()
                || command.reasonCode().length() > 64) {
            throw validation("reasonCode must be a nonblank value of at most 64 characters.");
        }
        if (command.reasonText() != null && command.reasonText().length() > 2000) {
            throw validation("reasonText must be at most 2000 characters.");
        }
        if (command.mode() == CorrectionMode.REVERSAL_ONLY && command.replacement() != null) {
            throw validation("REVERSAL_ONLY corrections cannot include a replacement.");
        }
        if (command.mode() == CorrectionMode.REPLACE) {
            validateReplacement(command.replacement());
        }
    }

    private static void validateReplacement(Replacement replacement) {
        if (replacement == null || replacement.amount() == null
                || replacement.currency() == null
                || !replacement.currency().matches("^[A-Z]{3}$")) {
            throw validation("REPLACE requires a valid amount and three-letter currency.");
        }
        var amount = replacement.amount();
        if (amount.scale() > 8 || amount.precision() - amount.scale() > 12) {
            throw validation("replacement.amount must fit DECIMAL(20,8).");
        }
        var targetCount = (replacement.projectId() == null ? 0 : 1)
                + (replacement.costCenterId() == null ? 0 : 1)
                + (replacement.teamId() == null ? 0 : 1);
        if (targetCount != 1) {
            throw validation("replacement must provide exactly one target.");
        }
        if (replacement.projectId() != null && replacement.projectId() <= 0
                || replacement.costCenterId() != null && replacement.costCenterId() <= 0
                || replacement.teamId() != null && replacement.teamId() <= 0) {
            throw validation("replacement target ids must be positive integers.");
        }
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid correction request", detail);
    }

    private static DomainException stateConflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Correction state conflict", detail);
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
}
