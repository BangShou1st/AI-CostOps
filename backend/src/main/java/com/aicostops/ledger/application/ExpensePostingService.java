package com.aicostops.ledger.application;

import com.aicostops.allocation.application.AllocationPostingPort;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.budget.application.CommitmentConsumeService;
import com.aicostops.budget.application.LedgerBudgetPort;
import com.aicostops.budget.application.LedgerBudgetPort.BudgetSelection;
import com.aicostops.budget.application.LedgerBudgetPort.EntryScopeAmount;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.expense.application.ExpensePostingPort;
import com.aicostops.expense.application.ExpensePostingPort.ExpensePostingSource;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.ledger.application.LedgerPostingCommands.CommitmentLink;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingDetail;
import com.aicostops.ledger.domain.LedgerEntryType;
import com.aicostops.ledger.domain.LedgerSourceType;
import com.aicostops.ledger.infrastructure.LedgerPostingMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ACID expense posting orchestration; the expense module owns APPROVED -> POSTED. */
@Service("ledgerExpensePostingService")
public class ExpensePostingService {

    private static final String PERMISSION_EXPENSE_POST = "EXPENSE_POST";
    private static final String PERMISSION_LEDGER_POST = "LEDGER_POST";
    private static final int MAX_DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpensePostingPort expenses;
    private final AllocationPostingPort allocations;
    private final LedgerBudgetPort budgets;
    private final LedgerPostingMapper ledger;
    private final CommitmentConsumeService commitmentConsume;
    private final LedgerAuditPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ExpensePostingService(
            AuthorizationContextService authorizationContexts,
            ExpensePostingPort expenses,
            AllocationPostingPort allocations,
            LedgerBudgetPort budgets,
            LedgerPostingMapper ledger,
            CommitmentConsumeService commitmentConsume,
            LedgerAuditPort audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.expenses = expenses;
        this.allocations = allocations;
        this.budgets = budgets;
        this.ledger = ledger;
        this.commitmentConsume = commitmentConsume;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public LedgerPostingDetail post(AuthenticatedUser user, long expenseId,
            PostSourceCommand command) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_POST);
        authorization.requireOrg(context, PERMISSION_LEDGER_POST);
        var requested = command == null ? new PostSourceCommand(List.of()) : command;
        validateLinksShape(requested.commitmentLinks());

        var preSource = expenses.load(context.organizationId(), expenseId);
        var decisionId = requireDecisionPointer(preSource);
        var postingKey = "EXPENSE:" + expenseId;
        var existing = ledger.selectPostingByKey(context.organizationId(), postingKey);
        if (existing != null) {
            return detail(existing);
        }

        var preAllocation = allocations.load(context.organizationId(), decisionId);
        var preEntries = preAllocation.lines().stream().map(ExpensePostingService::toScopeAmount).toList();

        try {
            return withDeadlockRetry(() -> transactions.execute(status -> postInTransaction(
                    context.organizationId(), context.userId(), context.organizationMemberId(),
                    expenseId, decisionId, requested, preSource, preAllocation, preEntries)));
        } catch (DuplicateKeyException concurrentPostingKey) {
            return transactions.execute(status -> detail(ledger.selectPostingByKey(
                    context.organizationId(), "EXPENSE:" + expenseId)));
        }
    }

    private LedgerPostingDetail postInTransaction(long organizationId, long actorUserId,
            long actorMemberId, long expenseId, long decisionId, PostSourceCommand command,
            ExpensePostingSource preSource,
            AllocationPostingPort.ConfirmedAllocation preAllocation,
            List<EntryScopeAmount> preEntries) {
        var period = budgets.lockOpenPeriodAt(organizationId, effectiveAt(preSource));
        var selections = budgets.resolveSelections(organizationId, period.id(), preEntries);
        var budgetIds = selections.stream().map(BudgetSelection::budget).filter(Objects::nonNull)
                .map(Budget::id).toList();
        var lockedBudgets = budgets.lockBudgets(organizationId, budgetIds);
        var lockedBudgetById = lockedBudgets.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Budget::id, budget -> budget));
        var linksByLineId = command.commitmentLinks().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(CommitmentLink::allocationLineId,
                        link -> link));
        var lockedCommitments = budgets.lockCommitments(organizationId,
                command.commitmentLinks().stream().map(CommitmentLink::commitmentId).toList());
        var lockedCommitmentById = lockedCommitments.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(BudgetCommitment::id,
                        commitment -> commitment));

        var postingKey = "EXPENSE:" + expenseId;
        var existing = ledger.selectPostingByKey(organizationId, postingKey);
        if (existing != null) {
            return detail(existing);
        }

        // Frozen lock order: period -> budgets -> commitments -> source -> decision -> lines.
        var source = expenses.lockAndRequireApproved(organizationId, expenseId, decisionId);
        var allocation = allocations.lockConfirmed(organizationId, decisionId,
                AllocationSubjectType.EXPENSE_CLAIM, expenseId);
        validateRevalidatedSource(source, allocation, preSource, preAllocation, period);
        validateCommitmentLinks(command.commitmentLinks(), allocation.lines(), selections,
                lockedBudgetById, lockedCommitmentById);

        var now = clock.instant();
        ledger.insertPosting(organizationId, postingKey, LedgerSourceType.EXPENSE_CLAIM.name(),
                expenseId, decisionId, period.id(), "POSTED", actorMemberId, now, now);
        var postingId = ledger.lastInsertId();
        var selectionByIndex = new HashMap<Integer, Budget>();
        selections.forEach(selection -> selectionByIndex.put(selection.entryIndex(),
                selection.budget() == null ? null : lockedBudgetById.get(selection.budget().id())));
        var insertedEntries = new java.util.ArrayList<com.aicostops.ledger.domain.LedgerEntry>();
        for (var line : allocation.lines()) {
            var budget = selectionByIndex.get(line.lineIndex());
            var budgetId = budget == null ? null : budget.id();
            ledger.insertEntry(organizationId, postingId, line.lineIndex(),
                    entryType(line.allocatedAmount()).name(), line.allocatedAmount(), line.currency(),
                    line.projectId(), line.costCenterId(), line.teamId(), budgetId,
                    null, expenseId, line.id(), null, null, now);
            var entryId = ledger.lastEntryId();
            var entry = ledger.selectEntryByIdForUpdate(organizationId, entryId);
            insertedEntries.add(entry);
            if (budgetId != null) {
                budgets.incrementActual(organizationId, budgetId, line.allocatedAmount(), now);
            }
            var link = linksByLineId.get(line.id());
            if (link != null) {
                commitmentConsume.consume(new CommitmentConsumeService.ConsumeCommand(
                        organizationId, link.commitmentId(), line.allocatedAmount(), entryId));
            }
        }
        audit.expensePosted(organizationId, actorUserId, postingId, expenseId, decisionId,
                insertedEntries.size(), source.currency());
        expenses.markPosted(organizationId, expenseId, source.version(), now);
        return detail(ledger.selectPostingByIdAndOrganization(organizationId, postingId));
    }

    private LedgerPostingDetail detail(com.aicostops.ledger.domain.LedgerPosting posting) {
        if (posting == null) {
            throw new IllegalStateException("A persisted Ledger posting must be readable");
        }
        return new LedgerPostingDetail(posting,
                ledger.selectEntriesByPostingId(posting.organizationId(), posting.id()));
    }

    private static void validateLinksShape(List<CommitmentLink> links) {
        var seen = new HashSet<Long>();
        for (var link : links) {
            if (link == null || link.allocationLineId() <= 0 || link.commitmentId() <= 0) {
                throw validation("Commitment links require positive allocationLineId and commitmentId.");
            }
            if (!seen.add(link.allocationLineId())) {
                throw validation("At most one commitment link is allowed per allocation line.");
            }
        }
    }

    private static void validateCommitmentLinks(List<CommitmentLink> links, List<AllocationLine> lines,
            List<BudgetSelection> selections, Map<Long, Budget> budgets,
            Map<Long, BudgetCommitment> commitments) {
        var lineById = lines.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(AllocationLine::id, line -> line));
        var budgetByIndex = new HashMap<Integer, Budget>();
        selections.forEach(selection -> budgetByIndex.put(selection.entryIndex(),
                selection.budget() == null ? null : budgets.get(selection.budget().id())));
        for (var link : links) {
            var line = lineById.get(link.allocationLineId());
            if (line == null) {
                throw validation("A commitment link must reference a line of the confirmed decision.");
            }
            if (line.allocatedAmount().signum() <= 0) {
                throw validation("Credits and zero-value lines cannot consume commitments.");
            }
            var selectedBudget = budgetByIndex.get(line.lineIndex());
            if (selectedBudget == null) {
                throw validation("A commitment link requires a selected Budget.");
            }
            var commitment = commitments.get(link.commitmentId());
            if (commitment == null || commitment.budgetId() != selectedBudget.id()) {
                throw validation("The linked commitment must belong to the selected Budget.");
            }
            if (!commitment.status().canConsume()) {
                throw stateConflict("The linked commitment is not consumable.");
            }
        }
    }

    private static void validateRevalidatedSource(ExpensePostingSource source,
            AllocationPostingPort.ConfirmedAllocation allocation, ExpensePostingSource preSource,
            AllocationPostingPort.ConfirmedAllocation preAllocation,
            com.aicostops.budget.domain.BillingPeriod period) {
        if (!source.expenseDate().equals(preSource.expenseDate())
                || source.amount().compareTo(preSource.amount()) != 0
                || !source.currency().equals(preSource.currency())
                || source.version() != preSource.version()
                || !period.covers(effectiveAt(source))) {
            throw stateConflict("The expense changed while posting.");
        }
        if (allocation.lines().size() != preAllocation.lines().size()) {
            throw stateConflict("The allocation changed while posting.");
        }
        for (var i = 0; i < allocation.lines().size(); i++) {
            var actual = allocation.lines().get(i);
            var expected = preAllocation.lines().get(i);
            if (actual.id() != expected.id() || actual.lineIndex() != expected.lineIndex()
                    || actual.allocatedAmount().compareTo(expected.allocatedAmount()) != 0
                    || !actual.currency().equals(expected.currency())
                    || !Objects.equals(actual.projectId(), expected.projectId())
                    || !Objects.equals(actual.costCenterId(), expected.costCenterId())
                    || !Objects.equals(actual.teamId(), expected.teamId())) {
                throw stateConflict("The allocation changed while posting.");
            }
        }
        var sum = allocation.lines().stream().map(AllocationLine::allocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(source.amount()) != 0) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_SUM_MISMATCH,
                    "Allocation sum mismatch", "Allocation lines must equal the expense amount.");
        }
        if (allocation.lines().stream().anyMatch(line -> !source.currency().equals(line.currency()))) {
            throw validation("All allocation lines must use the expense currency.");
        }
        if (allocation.lines().stream().anyMatch(line -> targetCount(line) != 1)) {
            throw validation("Every allocation line must have exactly one target.");
        }
    }

    private static EntryScopeAmount toScopeAmount(AllocationLine line) {
        if (line.projectId() != null) {
            return new EntryScopeAmount(line.lineIndex(), ScopeType.PROJECT, line.projectId(),
                    line.currency());
        }
        if (line.costCenterId() != null) {
            return new EntryScopeAmount(line.lineIndex(), ScopeType.COST_CENTER,
                    line.costCenterId(), line.currency());
        }
        if (line.teamId() != null) {
            return new EntryScopeAmount(line.lineIndex(), ScopeType.TEAM, line.teamId(),
                    line.currency());
        }
        throw validation("Every allocation line must have exactly one target.");
    }

    private static Instant effectiveAt(ExpensePostingSource source) {
        return source.expenseDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static int targetCount(AllocationLine line) {
        return (line.projectId() == null ? 0 : 1) + (line.costCenterId() == null ? 0 : 1)
                + (line.teamId() == null ? 0 : 1);
    }

    private static LedgerEntryType entryType(BigDecimal amount) {
        return amount.signum() < 0 ? LedgerEntryType.CREDIT : LedgerEntryType.COST;
    }

    private static long requireDecisionPointer(ExpensePostingSource source) {
        if (source.currentAllocationDecisionId() == null) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                    "Expense is not postable", "The expense has no current allocation decision.");
        }
        return source.currentAllocationDecisionId();
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid posting request", detail);
    }

    private static DomainException stateConflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Posting state conflict", detail);
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
