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
import java.time.Instant;
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
    private static final String PERMISSION_EXPENSE_SUBMIT_OWN = "EXPENSE_SUBMIT_OWN";

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

    /**
     * Submits a DRAFT (SUBMIT action, creates the approval case) or resubmits
     * a NEEDS_INFO expense (RESUBMIT action, reuses the case). The idempotency
     * operation is always EXPENSE_SUBMIT so a retried key replays regardless of
     * the intermediate state; the action type is chosen from the locked row.
     * Evidence must be attached and AVAILABLE before any submission.
     */
    public ExpenseDetail submit(AuthenticatedUser user, long expenseId,
            ExpenseCommands.SubmitExpenseCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_SUBMIT_OWN);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.submitRequestHash(context.organizationId(),
                context.organizationMemberId(), expenseId, command.expectedVersion());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_SUBMIT,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var claim = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            requireOwnedVersioned(context.organizationId(), context.organizationMemberId(),
                    claim, command.expectedVersion());
            requireEvidenceForSubmit(context.organizationId(), claim);
            var now = clock.instant();
            switch (claim.status()) {
                case DRAFT -> firstSubmit(context, claim, now);
                case NEEDS_INFO -> resubmit(context, claim, now);
                default -> throw notSubmittable();
            }
            var updated = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            var detail = toDetail(updated);
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    /**
     * Cancels a SUBMITTED expense (and its PENDING approval case). DRAFT /
     * NEEDS_INFO / APPROVED expenses cannot be canceled in M4.
     */
    public ExpenseDetail cancel(AuthenticatedUser user, long expenseId,
            ExpenseCommands.CancelExpenseCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_SUBMIT_OWN);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.cancelRequestHash(context.organizationId(),
                context.organizationMemberId(), expenseId, command.expectedVersion());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_CANCEL,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var claim = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            requireOwnedVersioned(context.organizationId(), context.organizationMemberId(),
                    claim, command.expectedVersion());
            if (claim.status() != ExpenseClaimStatus.SUBMITTED) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Expense cannot be canceled",
                        "Only a SUBMITTED expense can be canceled.");
            }
            if (claim.approvalCaseId() == null) {
                throw new IllegalStateException("A SUBMITTED expense must have an approval case");
            }
            var approvalCase = mapper.selectApprovalCaseByExpenseForUpdate(
                    context.organizationId(), claim.id());
            if (approvalCase == null
                    || approvalCase.status() != com.aicostops.expense.domain.ApprovalCaseStatus.PENDING) {
                throw new IllegalStateException("A SUBMITTED expense must have a PENDING approval case");
            }
            var now = clock.instant();
            if (mapper.updateStatusVersioned(context.organizationId(), expenseId,
                    command.expectedVersion(), "SUBMITTED", "CANCELED",
                    claim.approvalCaseId(), now) != 1) {
                throw staleVersion();
            }
            if (mapper.updateApprovalCaseStatus(context.organizationId(), approvalCase.id(),
                    "PENDING", "CANCELED", now) != 1) {
                throw staleVersion();
            }
            mapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                    context.organizationMemberId(), "CANCEL", "SUBMITTED", "CANCELED",
                    null, now);
            var updated = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            var detail = toDetail(updated);
            audit.canceled(context.organizationId(), context.userId(), detail.id(), detail.version());
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    private void firstSubmit(com.aicostops.iam.domain.AuthorizationContext context,
            ExpenseClaim claim, Instant now) {
        if (claim.approvalCaseId() != null) {
            throw new IllegalStateException("A DRAFT expense cannot already have an approval case");
        }
        mapper.insertApprovalCase(context.organizationId(), claim.id(), now);
        var approvalCaseId = mapper.selectApprovalCaseByExpense(context.organizationId(), claim.id()).id();
        if (mapper.updateStatusVersioned(context.organizationId(), claim.id(), claim.version(),
                "DRAFT", "SUBMITTED", approvalCaseId, now) != 1) {
            throw staleVersion();
        }
        mapper.insertApprovalAction(context.organizationId(), approvalCaseId,
                context.organizationMemberId(), "SUBMIT", "DRAFT", "SUBMITTED", null, now);
        audit.submitted(context.organizationId(), context.userId(), claim.id(), "SUBMIT",
                claim.version() + 1);
    }

    private void resubmit(com.aicostops.iam.domain.AuthorizationContext context,
            ExpenseClaim claim, Instant now) {
        if (claim.approvalCaseId() == null) {
            throw new IllegalStateException("A NEEDS_INFO expense must have an approval case");
        }
        var approvalCase = mapper.selectApprovalCaseByExpenseForUpdate(
                context.organizationId(), claim.id());
        if (approvalCase == null
                || approvalCase.status() != com.aicostops.expense.domain.ApprovalCaseStatus.NEEDS_INFO) {
            throw new IllegalStateException("A NEEDS_INFO expense must have a NEEDS_INFO approval case");
        }
        if (mapper.updateStatusVersioned(context.organizationId(), claim.id(), claim.version(),
                "NEEDS_INFO", "SUBMITTED", claim.approvalCaseId(), now) != 1) {
            throw staleVersion();
        }
        if (mapper.updateApprovalCaseStatus(context.organizationId(), approvalCase.id(),
                "NEEDS_INFO", "PENDING", now) != 1) {
            throw staleVersion();
        }
        mapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                context.organizationMemberId(), "RESUBMIT", "NEEDS_INFO", "SUBMITTED", null, now);
        audit.submitted(context.organizationId(), context.userId(), claim.id(), "RESUBMIT",
                claim.version() + 1);
    }

    private void requireEvidenceForSubmit(long organizationId, ExpenseClaim claim) {
        if (claim.evidenceId() == null) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Evidence is required",
                    "Attach the primary evidence before submitting the expense.");
        }
        if (!"AVAILABLE".equals(mapper.selectEvidenceStorageStatus(
                organizationId, claim.evidenceId()))) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Evidence is not available",
                    "The primary evidence must be AVAILABLE before submitting the expense.");
        }
    }

    private void requireOwnedVersioned(long organizationId, long ownerMemberId,
            ExpenseClaim claim, long expectedVersion) {
        if (claim == null || !claim.isOwnedBy(ownerMemberId)) {
            throw notFound();
        }
        if (claim.version() != expectedVersion) {
            throw staleVersion();
        }
    }

    private static DomainException notSubmittable() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Expense cannot be submitted",
                "Only a DRAFT or NEEDS_INFO expense can be submitted.");
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
