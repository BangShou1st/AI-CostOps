package com.aicostops.budget.application;

import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.budget.application.CommitmentIdempotencyStore.IdempotencyDecision;
import com.aicostops.budget.application.CommitmentReadModels.CommitmentDetail;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.BudgetDecimal;
import com.aicostops.budget.domain.BudgetStatus;
import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Budget commitment commands. Every command resolves the authorization
 * context fresh from MySQL (commitments are finance-sensitive), requires the
 * permission before any resource lookup (403), then finds the resource
 * organization-scoped (privacy 404), then enforces the grant scope against
 * the budget's own scope, then runs the state rules inside one MySQL
 * transaction.
 *
 * <p>Request (AIC-044 foundation) creates REQUESTED commitment + PENDING
 * approval case + SUBMIT action atomically and never touches
 * {@code budget.committed_amount}. Activation / reject / cancel / release /
 * consume are implemented in the later TDD stages of this branch.
 */
@Service
public class BudgetCommitmentCommandService {

    private static final int DEADLOCK_RETRIES = 3;
    private static final String PERMISSION_COMMITMENT_REQUEST = "COMMITMENT_REQUEST";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BudgetMapper budgetMapper;
    private final BudgetCommitmentMapper commitmentMapper;
    private final CommitmentIdempotency idempotency;
    private final CommitmentAuditPort audit;
    private final CommitmentResponseCodec responseCodec;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BudgetCommitmentCommandService(
            AuthorizationContextService authorizationContexts,
            BudgetMapper budgetMapper,
            BudgetCommitmentMapper commitmentMapper,
            CommitmentIdempotency idempotency,
            CommitmentAuditPort audit,
            CommitmentResponseCodec responseCodec,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.budgetMapper = budgetMapper;
        this.commitmentMapper = commitmentMapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * A new commitment request: one MySQL transaction creates the REQUESTED
     * commitment, its PENDING approval case, and the append-only SUBMIT
     * action, then audits and finalizes the idempotency row. The request
     * validates the budget (same org, ACTIVE, currency match, grant scope)
     * but never reserves capacity: committed_amount stays untouched until
     * activation.
     */
    public CommitmentDetail request(AuthenticatedUser user, RequestCommitmentCommand command,
            String idempotencyKey) {
        var context = authorizationContexts.fresh(user);
        requireAnyApplicableGrant(context, PERMISSION_COMMITMENT_REQUEST);
        idempotency.validateKey(idempotencyKey);
        var requestedAmount = requireMoney(command.requestedAmount(), "requestedAmount");
        if (requestedAmount.signum() <= 0) {
            throw validation("requestedAmount must be greater than zero.");
        }
        var requestHash = idempotency.requestRequestHash(context.organizationId(),
                context.organizationMemberId(), command.budgetId(), requestedAmount,
                command.currency());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(),
                    CommitmentIdempotency.OPERATION_COMMITMENT_REQUEST,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var budget = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                    command.budgetId());
            if (budget == null) {
                throw notFound("The budget is not available in the current organization.");
            }
            authorization.requireResource(context, PERMISSION_COMMITMENT_REQUEST,
                    budget.scopeType(), budget.scopeId());
            if (budget.status() != BudgetStatus.ACTIVE) {
                throw stateConflict("Budget is not active",
                        "The budget must be ACTIVE before requesting a commitment.");
            }
            if (!command.currency().equals(budget.currency())) {
                throw validation("The request currency must match the budget currency.");
            }
            var now = clock.instant();
            commitmentMapper.insert(context.organizationId(), budget.id(), requestedAmount, now);
            var commitmentId = commitmentMapper.lastInsertId();
            commitmentMapper.insertApprovalCaseForCommitment(context.organizationId(),
                    commitmentId, now);
            var approvalCase = commitmentMapper.selectApprovalCaseByCommitment(
                    context.organizationId(), commitmentId);
            if (approvalCase == null) {
                throw new IllegalStateException("A created approval case must be readable");
            }
            commitmentMapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                    context.organizationMemberId(), "SUBMIT", "NONE", "REQUESTED", null, now);
            var detail = toDetail(commitmentMapper.selectByIdAndOrganization(
                    context.organizationId(), commitmentId), approvalCase);
            audit.requested(context.organizationId(), context.userId(), commitmentId,
                    budget.id(), requestedAmount);
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    // -- shared helpers --------------------------------------------------------

    protected CommitmentDetail toDetail(BudgetCommitment commitment,
            com.aicostops.budget.domain.CommitmentApprovalCase approvalCase) {
        var history = approvalCase == null ? List.<com.aicostops.budget.domain.CommitmentApprovalAction>of()
                : commitmentMapper.selectApprovalActionsByCase(commitment.organizationId(),
                        approvalCase.id());
        return CommitmentDetail.from(commitment, approvalCase, history);
    }

    /** 403 before any resource lookup: any applicable grant must exist. */
    protected static void requireAnyApplicableGrant(AuthorizationContext context,
            String permissionCode) {
        var applicableScopes = M1AdminPermissionPolicy.applicableScopes(permissionCode);
        var granted = context.grants().stream().anyMatch(grant ->
                grant.permissionCode().equals(permissionCode)
                        && applicableScopes.contains(grant.scopeType()));
        if (!granted) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Permission is required",
                    "The required permission is not granted at an applicable scope.");
        }
    }

    protected static BigDecimal requireMoney(BigDecimal value, String field) {
        try {
            return BudgetDecimal.money(value);
        } catch (IllegalArgumentException notExactlyRepresentable) {
            throw validation(notExactlyRepresentable.getMessage());
        }
    }

    protected static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Commitment validation failed", detail);
    }

    protected static DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Commitment not found", detail);
    }

    protected static DomainException stateConflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                title, detail);
    }

    protected static DomainException insufficientBudget(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.BUDGET_INSUFFICIENT,
                "Budget available is insufficient", detail);
    }

    protected static DomainException periodNotOpen(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                "Billing period is not open", detail);
    }

    protected static DomainException staleVersion() {
        return stateConflict("Commitment version conflict",
                "The commitment was modified by another request; reload and retry.");
    }

    protected <T> T executeWithDeadlockRetry(Supplier<T> operation) {
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

    protected Budget requireReadableBudget(long organizationId, long budgetId) {
        var budget = budgetMapper.selectByIdAndOrganization(organizationId, budgetId);
        if (budget == null) {
            throw new IllegalStateException("A referenced budget must be readable");
        }
        return budget;
    }
}
