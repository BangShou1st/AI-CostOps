package com.aicostops.budget.application;

import com.aicostops.attribution.application.AllocationTargetDirectory;
import com.aicostops.budget.application.BudgetCommands.CreateBudgetCommand;
import com.aicostops.budget.application.BudgetCommands.UpdateBudgetCommand;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Budget management commands: create with a natural identity constraint and
 * server-side scope validation, and total-amount updates with an optimistic
 * version CAS. {@code BUDGET_MANAGE} is a sensitive permission, so both
 * commands resolve the authorization context fresh from MySQL — never from a
 * stale cache.
 *
 * <p>{@code actual_amount} / {@code committed_amount} are financial counters
 * owned exclusively by ledger posting / commitment transactions; this service
 * never writes them.
 */
@Service
public class BudgetCommandService {

    private static final String PERMISSION_BUDGET_MANAGE = "BUDGET_MANAGE";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationTargetDirectory targets;
    private final BudgetMapper mapper;
    private final BudgetAuditPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BudgetCommandService(
            AuthorizationContextService authorizationContexts,
            AllocationTargetDirectory targets,
            BudgetMapper mapper,
            BudgetAuditPort audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.targets = targets;
        this.mapper = mapper;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Budget create(AuthenticatedUser user, CreateBudgetCommand command) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_BUDGET_MANAGE);
        validateScope(context.organizationId(), command);
        if (!mapper.existsBillingPeriod(context.organizationId(), command.billingPeriodId())) {
            throw validation("The billing period must exist in the current organization.");
        }
        var now = clock.instant();
        try {
            mapper.insert(context.organizationId(), command.billingPeriodId(),
                    command.scopeType().name(), command.scopeId(), command.currency(),
                    command.totalAmount(), now);
        } catch (DuplicateKeyException duplicateIdentity) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Budget identity conflict",
                    "A budget for this organization, billing period, scope, and currency "
                            + "already exists.");
        }
        var created = requireReadable(context.organizationId(), mapper.lastInsertId());
        audit.created(context.organizationId(), context.userId(), created.id(),
                created.currency(), created.scopeType(), created.scopeId(),
                created.totalAmount());
        return created;
    }

    public Budget update(AuthenticatedUser user, long budgetId, UpdateBudgetCommand command) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_BUDGET_MANAGE);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var existing = mapper.selectByIdForUpdate(context.organizationId(), budgetId);
            if (existing == null) {
                throw notFound();
            }
            if (mapper.updateTotalAmount(context.organizationId(), budgetId,
                    command.expectedVersion(), command.totalAmount(), clock.instant()) != 1) {
                throw staleVersion();
            }
            var updated = requireReadable(context.organizationId(), budgetId);
            audit.totalChanged(context.organizationId(), context.userId(), updated.id(),
                    updated.version(), updated.totalAmount());
            return updated;
        }));
    }

    /**
     * Scope targets must be real, same-organization, ACTIVE resources; the
     * ORG scope must be the budget's own organization. The request ids are
     * never trusted as-is.
     */
    private void validateScope(long organizationId, CreateBudgetCommand command) {
        var valid = switch (command.scopeType()) {
            case ORG -> command.scopeId() == organizationId;
            case PROJECT -> targets.activeProjectExists(organizationId, command.scopeId());
            case TEAM -> targets.activeTeamExists(organizationId, command.scopeId());
            case COST_CENTER -> targets.activeCostCenterExists(organizationId, command.scopeId());
        };
        if (!valid) {
            throw validation("The budget scope must be an ACTIVE " + command.scopeType()
                    + " of the current organization.");
        }
    }

    private Budget requireReadable(long organizationId, long budgetId) {
        var budget = mapper.selectByIdAndOrganization(organizationId, budgetId);
        if (budget == null) {
            throw new IllegalStateException("A just-written budget must be readable");
        }
        return budget;
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Budget validation failed", detail);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Budget not found", "The budget is not available in the current organization.");
    }

    private static DomainException staleVersion() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Budget version conflict",
                "The budget was modified by another request; reload and retry.");
    }

    private <T> T executeWithDeadlockRetry(Supplier<T> operation) {
        for (var attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DeadlockLoserDataAccessException deadlock) {
                if (attempt >= 3) {
                    throw deadlock;
                }
            }
        }
    }
}