package com.aicostops.budget.application;

import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.budget.application.CommitmentIdempotencyStore.IdempotencyDecision;
import com.aicostops.budget.application.CommitmentReadModels.CommitmentDetail;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.BudgetCommitmentStatus;
import com.aicostops.budget.domain.BudgetDecimal;
import com.aicostops.budget.domain.BudgetStatus;
import com.aicostops.budget.domain.CommitmentApprovalCase;
import com.aicostops.budget.domain.CommitmentApprovalCaseStatus;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
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
    private static final String PERMISSION_COMMITMENT_APPROVE = "COMMITMENT_APPROVE";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BudgetMapper budgetMapper;
    private final BudgetCommitmentMapper commitmentMapper;
    private final BillingPeriodMapper billingPeriodMapper;
    private final CommitmentIdempotency idempotency;
    private final CommitmentAuditPort audit;
    private final CommitmentResponseCodec responseCodec;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BudgetCommitmentCommandService(
            AuthorizationContextService authorizationContexts,
            BudgetMapper budgetMapper,
            BudgetCommitmentMapper commitmentMapper,
            BillingPeriodMapper billingPeriodMapper,
            CommitmentIdempotency idempotency,
            CommitmentAuditPort audit,
            CommitmentResponseCodec responseCodec,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.budgetMapper = budgetMapper;
        this.commitmentMapper = commitmentMapper;
        this.billingPeriodMapper = billingPeriodMapper;
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

    /**
     * Atomic activation — the highest-risk transaction of AIC-044. One MySQL
     * transaction, in the frozen lock order BillingPeriod → Budget →
     * Commitment → ApprovalCase:
     *
     * <pre>
     * resolve commitment → budget → billing_period
     * SELECT billing_period ... FOR UPDATE   (serializes against future Close)
     * require OPEN (half-open window)
     * budget FOR UPDATE
     * commitment FOR UPDATE + state/version revalidation
     * UPDATE budget SET committed=committed+amount, version+1
     *   WHERE id=? AND status='ACTIVE' AND total-actual-committed >= amount
     * commitment REQUESTED → ACTIVE (approved = remaining = requested)
     * approval_case PENDING → APPROVED
     * approval_action APPROVE (append-only)
     * audit + idempotency finalize
     * </pre>
     *
     * <p>When the conditional UPDATE affects zero rows the loser is
     * classified by re-reading the locked budget: insufficient available →
     * 409 BUDGET_INSUFFICIENT, non-ACTIVE status → 409 STATE_CONFLICT. A
     * concurrent loser on the commitment row itself hits the status/version
     * CAS and gets 409 STATE_CONFLICT. Approval and counter can never be
     * split: everything is one transaction.
     */
    public CommitmentDetail approve(AuthenticatedUser user, long commitmentId,
            ApproveCommitmentCommand command, String idempotencyKey) {
        var context = authorizationContexts.fresh(user);
        requireAnyApplicableGrant(context, PERMISSION_COMMITMENT_APPROVE);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.approveRequestHash(context.organizationId(),
                context.organizationMemberId(), commitmentId, command.expectedVersion());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(),
                    CommitmentIdempotency.OPERATION_COMMITMENT_APPROVE,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var commitment = commitmentMapper.selectByIdAndOrganization(
                    context.organizationId(), commitmentId);
            if (commitment == null) {
                throw notFound("The commitment is not available in the current organization.");
            }
            var budget = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                    commitment.budgetId());
            if (budget == null) {
                throw notFound("The budget is not available in the current organization.");
            }
            authorization.requireResource(context, PERMISSION_COMMITMENT_APPROVE,
                    budget.scopeType(), budget.scopeId());

            var now = clock.instant();
            // 1. BillingPeriod lock + OPEN guard inside the same transaction:
            //    the OPEN check and the budget mutation cannot race with Close.
            var period = billingPeriodMapper.selectByIdForUpdate(
                    context.organizationId(), budget.billingPeriodId());
            if (period == null || !period.covers(now)) {
                throw stateConflict("No covering billing period",
                        "The budget's billing period does not cover the transaction time.");
            }
            if (period.status() != BillingPeriodStatus.OPEN) {
                throw periodNotOpen("The billing period of the budget is "
                        + period.status() + "; activation requires an OPEN period.");
            }
            // 2. Budget lock + revalidation.
            var budgetLocked = budgetMapper.selectByIdForUpdate(context.organizationId(),
                    budget.id());
            if (budgetLocked.status() != BudgetStatus.ACTIVE) {
                throw stateConflict("Budget is not active",
                        "The budget must be ACTIVE before activation.");
            }
            // 3. Commitment lock + state/version revalidation.
            var commitmentLocked = commitmentMapper.selectByIdForUpdate(
                    context.organizationId(), commitmentId);
            if (commitmentLocked.version() != command.expectedVersion()) {
                throw staleVersion();
            }
            if (!commitmentLocked.status().canActivate()) {
                throw stateConflict("Commitment cannot be activated",
                        "Only a REQUESTED commitment can be activated; the commitment is "
                                + commitmentLocked.status() + ".");
            }
            // 4. Atomic conditional UPDATE: no Java check-then-act anywhere.
            var amount = commitmentLocked.requestedAmount();
            var updated = budgetMapper.incrementCommitted(context.organizationId(), budget.id(),
                    amount, now);
            if (updated != 1) {
                // Classify the loser on the row already locked FOR UPDATE in
                // this transaction: it is a current read, so the availability
                // decision is based on the latest committed state, never on
                // the REPEATABLE-READ snapshot of this transaction.
                if (budgetLocked.status() != BudgetStatus.ACTIVE) {
                    throw stateConflict("Budget is not active",
                            "The budget must be ACTIVE before activation.");
                }
                if (budgetLocked.available().compareTo(amount) < 0) {
                    throw insufficientBudget("The budget available ("
                            + budgetLocked.available().toPlainString()
                            + ") is insufficient for the requested amount "
                            + amount.toPlainString() + ".");
                }
                throw stateConflict("Budget activation conflict",
                        "The budget could not be committed; a concurrent activation consumed "
                                + "the available capacity.");
            }
            // 5. Commitment REQUESTED → ACTIVE with exact amounts.
            if (commitmentMapper.updateActivate(context.organizationId(), commitmentId,
                    amount, amount, now) != 1) {
                throw stateConflict("Commitment activation conflict",
                        "The commitment was no longer REQUESTED when activation applied.");
            }
            // 6. Approval case PENDING → APPROVED, exactly one APPROVE action.
            var approvalCase = commitmentMapper.selectApprovalCaseByCommitmentForUpdate(
                    context.organizationId(), commitmentId);
            if (approvalCase == null
                    || approvalCase.status() != CommitmentApprovalCaseStatus.PENDING) {
                throw stateConflict("Approval case conflict",
                        "The commitment's approval case must be PENDING to activate.");
            }
            commitmentMapper.updateApprovalCaseStatus(context.organizationId(),
                    approvalCase.id(), "PENDING", "APPROVED", now);
            commitmentMapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                    context.organizationMemberId(), "APPROVE", "REQUESTED", "ACTIVE", null, now);
            var updatedCase = commitmentMapper.selectApprovalCaseByCommitment(
                    context.organizationId(), commitmentId);
            var detail = toDetail(commitmentMapper.selectByIdAndOrganization(
                    context.organizationId(), commitmentId), updatedCase);
            audit.activated(context.organizationId(), context.userId(), commitmentId,
                    budget.id(), amount, updatedCase.id(), "REQUESTED", "ACTIVE");
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
