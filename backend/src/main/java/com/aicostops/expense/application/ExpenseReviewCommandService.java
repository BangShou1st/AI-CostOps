package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseCommands.ApproveExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RejectExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RequestInfoCommand;
import com.aicostops.expense.application.ExpenseIdempotencyStore.IdempotencyDecision;
import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.domain.ApprovalCaseStatus;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.expense.infrastructure.ExpenseApprovalMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExpenseReviewCommandService {

    /** Each review action starts from SUBMITTED/PENDING and owns both target states. */
    private enum ReviewAction {
        REQUEST_INFO(ExpenseClaimStatus.NEEDS_INFO, ApprovalCaseStatus.NEEDS_INFO),
        APPROVE(ExpenseClaimStatus.APPROVED, ApprovalCaseStatus.APPROVED),
        REJECT(ExpenseClaimStatus.REJECTED, ApprovalCaseStatus.REJECTED);

        private final ExpenseClaimStatus expenseStatus;
        private final ApprovalCaseStatus approvalStatus;

        ReviewAction(ExpenseClaimStatus expenseStatus, ApprovalCaseStatus approvalStatus) {
            this.expenseStatus = expenseStatus;
            this.approvalStatus = approvalStatus;
        }
    }

    private static final String PERMISSION_EXPENSE_REVIEW = "EXPENSE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpenseClaimMapper mapper;
    private final ExpenseApprovalMapper approvals;
    private final ExpenseCloseAdmissionPort closeAdmission;
    private final ExpenseIdempotency idempotency;
    private final ExpenseAuditPort audit;
    private final ExpenseResponseCodec responseCodec;
    private final ExpenseCommandTransactions transactions;
    private final ExpenseDetailAssembler details;
    private final Clock clock;

    public ExpenseReviewCommandService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper mapper,
            ExpenseApprovalMapper approvals,
            ExpenseCloseAdmissionPort closeAdmission,
            ExpenseIdempotency idempotency,
            ExpenseAuditPort audit,
            ExpenseResponseCodec responseCodec,
            ExpenseCommandTransactions transactions,
            ExpenseDetailAssembler details,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.approvals = approvals;
        this.closeAdmission = closeAdmission;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.transactions = transactions;
        this.details = details;
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
        return transactions.execute(() -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_REQUEST_INFO,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var result = reviewTransition(context, expenseId, command.expectedVersion(),
                    ReviewAction.REQUEST_INFO, command.comment(), decision, null);
            audit.reviewed(context.organizationId(), context.userId(), expenseId,
                    result.version(), "REQUEST_INFO", command.comment());
            return result;
        });
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
        return transactions.execute(() -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_APPROVE,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var result = reviewTransition(context, expenseId, command.expectedVersion(),
                    ReviewAction.APPROVE, null, decision, effectiveAt);
            audit.reviewed(context.organizationId(), context.userId(), expenseId,
                    result.version(), "APPROVE", null);
            return result;
        });
    }

    public ExpenseDetail reject(AuthenticatedUser user, long expenseId,
            RejectExpenseCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.rejectRequestHash(context.organizationId(),
                context.organizationMemberId(), expenseId, command.expectedVersion(),
                command.comment());
        return transactions.execute(() -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), ExpenseIdempotency.OPERATION_REJECT,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var result = reviewTransition(context, expenseId, command.expectedVersion(),
                    ReviewAction.REJECT, command.comment(), decision, null);
            audit.reviewed(context.organizationId(), context.userId(), expenseId,
                    result.version(), "REJECT", command.comment());
            return result;
        });
    }

    private ExpenseDetail reviewTransition(AuthorizationContext context, long expenseId,
            long expectedVersion, ReviewAction action, String comment, IdempotencyDecision decision,
            Instant financialEffectiveAt) {
        if (financialEffectiveAt != null) {
            closeAdmission.lockOpenAt(context.organizationId(), financialEffectiveAt);
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
        var approvalCase = approvals.selectApprovalCaseByExpenseForUpdate(
                context.organizationId(), claim.id());
        if (approvalCase == null) {
            throw new IllegalStateException("The approval case of a reviewable expense must exist");
        }
        if (claim.version() != expectedVersion) {
            throw staleVersion();
        }
        if (claim.status() != ExpenseClaimStatus.SUBMITTED || approvalCase.status() != ApprovalCaseStatus.PENDING) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Expense review conflict",
                    "The expense is no longer in the expected review state.");
        }
        var now = clock.instant();
        if (mapper.updateStatusVersioned(context.organizationId(), expenseId, expectedVersion,
                ExpenseClaimStatus.SUBMITTED.name(), action.expenseStatus.name(), claim.approvalCaseId(), now) != 1) {
            throw staleVersion();
        }
        if (approvals.updateApprovalCaseStatus(context.organizationId(), approvalCase.id(),
                ApprovalCaseStatus.PENDING.name(), action.approvalStatus.name(), now) != 1) {
            throw staleVersion();
        }
        approvals.insertApprovalAction(context.organizationId(), approvalCase.id(),
                context.organizationMemberId(), action.name(), ExpenseClaimStatus.SUBMITTED.name(), action.expenseStatus.name(),
                comment, now);
        var updated = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
        var detail = details.assemble(updated);
        idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
        return detail;
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

}
