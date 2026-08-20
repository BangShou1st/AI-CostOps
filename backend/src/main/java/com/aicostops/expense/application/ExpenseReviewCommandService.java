package com.aicostops.expense.application;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.expense.application.ExpenseCommands.ApproveExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RejectExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RequestInfoCommand;
import com.aicostops.expense.application.ExpenseIdempotencyStore.IdempotencyDecision;
import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.domain.ApprovalCaseStatus;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExpenseReviewCommandService {

    private static final int DEADLOCK_RETRIES = 3;
    private static final String PERMISSION_EXPENSE_REVIEW = "EXPENSE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpenseClaimMapper mapper;
    private final BillingPeriodFinancialWriteFence periodFence;
    private final ExpenseIdempotency idempotency;
    private final ExpenseAuditPort audit;
    private final ExpenseResponseCodec responseCodec;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ExpenseReviewCommandService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper mapper,
            BillingPeriodFinancialWriteFence periodFence,
            ExpenseIdempotency idempotency,
            ExpenseAuditPort audit,
            ExpenseResponseCodec responseCodec,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.periodFence = periodFence;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ExpenseDetail requestInfo(AuthenticatedUser user, long expenseId,
            RequestInfoCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.requestInfoRequestHash(context.organizationId(),
                context.organizationMemberId(), expenseId, command.expectedVersion(),
                command.comment());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_REQUEST_INFO,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var result = reviewTransition(context, expenseId, command.expectedVersion(),
                    ExpenseClaimStatus.SUBMITTED, ExpenseClaimStatus.NEEDS_INFO,
                    ApprovalCaseStatus.PENDING, ApprovalCaseStatus.NEEDS_INFO,
                    "REQUEST_INFO", command.comment(), decision, null);
            audit.reviewed(context.organizationId(), context.userId(), expenseId,
                    result.version(), "REQUEST_INFO", command.comment());
            return result;
        }));
    }

    public ExpenseDetail approve(AuthenticatedUser user, long expenseId,
            ApproveExpenseCommand command, String idempotencyKey) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        idempotency.validateKey(idempotencyKey);
        var identity = mapper.selectByIdAndOrganization(context.organizationId(), expenseId);
        if (identity == null) {
            throw notFound();
        }
        var effectiveAt = identity.expenseDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        var requestHash = idempotency.approveRequestHash(context.organizationId(),
                context.organizationMemberId(), expenseId, command.expectedVersion());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_APPROVE,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var result = reviewTransition(context, expenseId, command.expectedVersion(),
                    ExpenseClaimStatus.SUBMITTED, ExpenseClaimStatus.APPROVED,
                    ApprovalCaseStatus.PENDING, ApprovalCaseStatus.APPROVED,
                    "APPROVE", null, decision, effectiveAt);
            audit.reviewed(context.organizationId(), context.userId(), expenseId,
                    result.version(), "APPROVE", null);
            return result;
        }));
    }

    public ExpenseDetail reject(AuthenticatedUser user, long expenseId,
            RejectExpenseCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.rejectRequestHash(context.organizationId(),
                context.organizationMemberId(), expenseId, command.expectedVersion(),
                command.comment());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_REJECT,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var result = reviewTransition(context, expenseId, command.expectedVersion(),
                    ExpenseClaimStatus.SUBMITTED, ExpenseClaimStatus.REJECTED,
                    ApprovalCaseStatus.PENDING, ApprovalCaseStatus.REJECTED,
                    "REJECT", command.comment(), decision, null);
            audit.reviewed(context.organizationId(), context.userId(), expenseId,
                    result.version(), "REJECT", command.comment());
            return result;
        }));
    }

    private ExpenseDetail reviewTransition(AuthorizationContext context, long expenseId,
            long expectedVersion, ExpenseClaimStatus fromExpense, ExpenseClaimStatus toExpense,
            ApprovalCaseStatus fromCase, ApprovalCaseStatus toCase,
            String actionType, String comment, IdempotencyDecision decision,
            Instant financialEffectiveAt) {
        if (financialEffectiveAt != null) {
            periodFence.lockOpenAt(context.organizationId(), financialEffectiveAt);
        }
        var claim = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
        if (claim == null) {
            throw notFound();
        }
        if (financialEffectiveAt != null
                && !claim.expenseDate().atStartOfDay(ZoneOffset.UTC).toInstant().equals(financialEffectiveAt)) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Expense effective date changed",
                    "Reload the expense before approving it.");
        }
        if (claim.approvalCaseId() == null) {
            throw new IllegalStateException("A reviewable expense must have an approval case");
        }
        var approvalCase = mapper.selectApprovalCaseByExpenseForUpdate(
                context.organizationId(), claim.id());
        if (approvalCase == null) {
            throw new IllegalStateException("The approval case of a reviewable expense must exist");
        }
        if (claim.version() != expectedVersion) {
            throw staleVersion();
        }
        if (claim.status() != fromExpense || approvalCase.status() != fromCase) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Expense review conflict",
                    "The expense is no longer in the expected review state.");
        }
        var now = clock.instant();
        if (mapper.updateStatusVersioned(context.organizationId(), expenseId, expectedVersion,
                fromExpense.name(), toExpense.name(), claim.approvalCaseId(), now) != 1) {
            throw staleVersion();
        }
        if (mapper.updateApprovalCaseStatus(context.organizationId(), approvalCase.id(),
                fromCase.name(), toCase.name(), now) != 1) {
            throw staleVersion();
        }
        mapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                context.organizationMemberId(), actionType, fromExpense.name(), toExpense.name(),
                comment, now);
        var updated = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
        var detail = toDetail(updated);
        idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
        return detail;
    }

    private ExpenseDetail toDetail(ExpenseClaim claim) {
        var approvalStatus = claim.approvalCaseId() == null ? null
                : mapper.selectApprovalCaseByExpense(claim.organizationId(), claim.id()).status();
        var decisionConfirmed = claim.currentAllocationDecisionId() == null ? false
                : "CONFIRMED".equals(mapper.selectDecisionStatus(
                        claim.organizationId(), claim.currentAllocationDecisionId()));
        var history = claim.approvalCaseId() == null ? List.<com.aicostops.expense.domain.ApprovalAction>of()
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
