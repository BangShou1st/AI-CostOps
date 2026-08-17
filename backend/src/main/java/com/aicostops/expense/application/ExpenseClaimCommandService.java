package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.EditExpenseCommand;
import com.aicostops.expense.application.ExpenseIdempotencyStore.IdempotencyDecision;
import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expense claim commands: idempotent DRAFT creation and optimistic-version
 * body edits. Every mutation locks the expense row FOR UPDATE first, rechecks
 * owner/state/version, then writes; the idempotency replay runs before those
 * checks so a retried key returns the first success response unchanged.
 */
@Service
public class ExpenseClaimCommandService {

    private static final int DEADLOCK_RETRIES = 3;
    private static final String PERMISSION_EXPENSE_CREATE_OWN = "EXPENSE_CREATE_OWN";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpenseClaimMapper mapper;
    private final ExpenseIdempotency idempotency;
    private final ExpenseAuditPort audit;
    private final ExpenseResponseCodec responseCodec;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ExpenseClaimCommandService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper mapper,
            ExpenseIdempotency idempotency,
            ExpenseAuditPort audit,
            ExpenseResponseCodec responseCodec,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ExpenseDetail create(AuthenticatedUser user, CreateExpenseCommand command,
            String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_CREATE_OWN);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.createRequestHash(context.organizationId(),
                context.organizationMemberId(), command.expenseDate(), command.amount(),
                command.currency());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_CREATE,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            mapper.insertClaim(context.organizationId(), context.organizationMemberId(),
                    command.expenseDate(), command.amount(), command.currency(), clock.instant());
            var claim = mapper.selectByIdAndOrganization(context.organizationId(),
                    mapper.lastInsertId());
            if (claim == null) {
                throw new IllegalStateException("A created expense must be readable in its organization");
            }
            var detail = ExpenseDetail.from(claim, null, false, List.of());
            audit.claimCreated(context.organizationId(), context.userId(), detail.id(),
                    detail.currency());
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    /**
     * Full-replacement body edit with an optimistic version CAS, following the
     * PUT replace-lines style: no Idempotency-Key, stale version is 409.
     */
    public ExpenseDetail edit(AuthenticatedUser user, long expenseId, EditExpenseCommand command) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_CREATE_OWN);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var claim = requireOwnedEditable(context.organizationId(),
                    context.organizationMemberId(), expenseId, command.expectedVersion());
            if (mapper.updateEditable(context.organizationId(), expenseId, command.expectedVersion(),
                    command.expenseDate(), command.amount(), command.currency(),
                    clock.instant()) != 1) {
                throw staleVersion();
            }
            var updated = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            var detail = toDetail(updated);
            audit.claimEdited(context.organizationId(), context.userId(), detail.id(),
                    detail.version(), detail.currency());
            return detail;
        }));
    }

    private ExpenseClaim requireOwnedEditable(long organizationId, long ownerMemberId,
            long expenseId, long expectedVersion) {
        var claim = mapper.selectByIdForUpdate(organizationId, expenseId);
        if (claim == null || !claim.isOwnedBy(ownerMemberId)) {
            throw notFound();
        }
        if (!claim.status().editableByOwner()) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Expense is not editable",
                    "Only a DRAFT or NEEDS_INFO expense can be edited.");
        }
        if (claim.version() != expectedVersion) {
            throw staleVersion();
        }
        return claim;
    }

    private ExpenseDetail toDetail(ExpenseClaim claim) {
        var approvalStatus = claim.approvalCaseId() == null ? null
                : mapper.selectApprovalCaseByExpense(claim.organizationId(), claim.id()).status();
        var decisionConfirmed = claim.currentAllocationDecisionId() == null ? false
                : "CONFIRMED".equals(mapper.selectDecisionStatus(
                        claim.organizationId(), claim.currentAllocationDecisionId()));
        var history = claim.approvalCaseId() == null ? List.<ApprovalAction>of()
                : mapper.selectApprovalActionsByCase(claim.organizationId(), claim.approvalCaseId());
        return ExpenseDetail.from(claim, approvalStatus, decisionConfirmed, history);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Expense not found",
                "The expense is not available to the current user.");
    }

    private static DomainException staleVersion() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Expense version conflict",
                "The expense was modified by another request; reload and retry.");
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
