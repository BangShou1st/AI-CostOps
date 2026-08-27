package com.aicostops.budget.application;

import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.CancelCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RejectCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.ReleaseCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.budget.application.CommitmentReadModels.CommitmentDetail;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.BudgetDecimal;
import com.aicostops.budget.domain.BudgetStatus;
import com.aicostops.budget.domain.CommitmentApprovalCaseStatus;
import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.observability.AiCostOpsMetrics;
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

@Service
public class BudgetCommitmentCommandService {

    private static final int DEADLOCK_RETRIES = 3;
    private static final String PERMISSION_COMMITMENT_REQUEST = "COMMITMENT_REQUEST";
    private static final String PERMISSION_COMMITMENT_APPROVE = "COMMITMENT_APPROVE";
    private static final String PERMISSION_COMMITMENT_RELEASE = "COMMITMENT_RELEASE";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BudgetMapper budgetMapper;
    private final BudgetCommitmentMapper commitmentMapper;
    private final BillingPeriodFinancialWriteFence periodFence;
    private final CommitmentIdempotency idempotency;
    private final CommitmentAuditPort audit;
    private final CommitmentResponseCodec responseCodec;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BudgetCommitmentCommandService(
            AuthorizationContextService authorizationContexts,
            BudgetMapper budgetMapper,
            BudgetCommitmentMapper commitmentMapper,
            BillingPeriodFinancialWriteFence periodFence,
            CommitmentIdempotency idempotency,
            CommitmentAuditPort audit,
            CommitmentResponseCodec responseCodec,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.budgetMapper = budgetMapper;
        this.commitmentMapper = commitmentMapper;
        this.periodFence = periodFence;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

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
            var identity = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                    command.budgetId());
            if (identity == null) {
                throw notFound("The budget is not available in the current organization.");
            }
            authorization.requireResource(context, PERMISSION_COMMITMENT_REQUEST,
                    identity.scopeType(), identity.scopeId());

            // REQUESTED commitments are a future Close blocker even before they
            // reserve capacity, so creation is an OPEN-period financial write.
            periodFence.lockOpenById(context.organizationId(), identity.billingPeriodId());
            var budget = budgetMapper.selectByIdForUpdate(context.organizationId(), identity.id());
            if (budget == null || budget.billingPeriodId() != identity.billingPeriodId()) {
                throw notFound("The budget is not available in the current organization.");
            }
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
            var budgetIdentity = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                    commitment.budgetId());
            if (budgetIdentity == null) {
                throw notFound("The budget is not available in the current organization.");
            }
            authorization.requireResource(context, PERMISSION_COMMITMENT_APPROVE,
                    budgetIdentity.scopeType(), budgetIdentity.scopeId());

            var now = clock.instant();
            periodFence.lockOpenById(context.organizationId(), budgetIdentity.billingPeriodId());
            var budgetLocked = budgetMapper.selectByIdForUpdate(context.organizationId(),
                    budgetIdentity.id());
            if (budgetLocked == null || budgetLocked.status() != BudgetStatus.ACTIVE) {
                throw stateConflict("Budget is not active",
                        "The budget must be ACTIVE before activation.");
            }
            var commitmentLocked = commitmentMapper.selectByIdForUpdate(
                    context.organizationId(), commitmentId);
            if (commitmentLocked == null || commitmentLocked.budgetId() != budgetLocked.id()) {
                throw notFound("The commitment is not available in the current organization.");
            }
            if (commitmentLocked.version() != command.expectedVersion()) {
                throw staleVersion();
            }
            if (!commitmentLocked.status().canActivate()) {
                throw stateConflict("Commitment cannot be activated",
                        "Only a REQUESTED commitment can be activated; the commitment is "
                                + commitmentLocked.status() + ".");
            }
            var amount = commitmentLocked.requestedAmount();
            if (budgetMapper.incrementCommitted(context.organizationId(), budgetLocked.id(),
                    amount, now) != 1) {
                var currentBudget = budgetMapper.selectByIdForUpdate(
                        context.organizationId(), budgetLocked.id());
                if (currentBudget.status() != BudgetStatus.ACTIVE) {
                    throw stateConflict("Budget is not active",
                            "The budget must be ACTIVE before activation.");
                }
                if (currentBudget.available().compareTo(amount) < 0) {
                    metrics.budgetActivation("CONFLICT");
                    throw insufficientBudget("The budget available ("
                            + currentBudget.available().toPlainString()
                            + ") is insufficient for the requested amount "
                            + amount.toPlainString() + ".");
                }
                throw stateConflict("Budget activation conflict",
                        "The budget could not be committed; a concurrent activation consumed the available capacity.");
            }
            if (commitmentMapper.updateActivate(context.organizationId(), commitmentId,
                    amount, amount, now) != 1) {
                throw stateConflict("Commitment activation conflict",
                        "The commitment was no longer REQUESTED when activation applied.");
            }
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
                    budgetLocked.id(), amount, updatedCase.id(), "REQUESTED", "ACTIVE");
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            metrics.budgetActivation("ACTIVATED");
            return detail;
        }));
    }

    public CommitmentDetail reject(AuthenticatedUser user, long commitmentId,
            RejectCommitmentCommand command, String idempotencyKey) {
        var context = authorizationContexts.fresh(user);
        requireAnyApplicableGrant(context, PERMISSION_COMMITMENT_APPROVE);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.rejectRequestHash(context.organizationId(),
                context.organizationMemberId(), commitmentId, command.expectedVersion(),
                command.comment());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(),
                    CommitmentIdempotency.OPERATION_COMMITMENT_REJECT,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var budget = requireVisibleBudget(context, commitmentId,
                    PERMISSION_COMMITMENT_APPROVE);
            var now = clock.instant();
            var commitmentLocked = commitmentMapper.selectByIdForUpdate(
                    context.organizationId(), commitmentId);
            if (commitmentLocked.version() != command.expectedVersion()) {
                throw staleVersion();
            }
            if (!commitmentLocked.status().canRejectOrCancel()) {
                throw stateConflict("Commitment cannot be rejected",
                        "Only a REQUESTED commitment can be rejected; the commitment is "
                                + commitmentLocked.status() + ".");
            }
            if (commitmentMapper.updateReject(context.organizationId(), commitmentId, now) != 1) {
                throw stateConflict("Commitment rejection conflict",
                        "The commitment was no longer REQUESTED when the rejection applied.");
            }
            var approvalCase = requirePendingCase(context.organizationId(), commitmentId);
            commitmentMapper.updateApprovalCaseStatus(context.organizationId(),
                    approvalCase.id(), "PENDING", "REJECTED", now);
            commitmentMapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                    context.organizationMemberId(), "REJECT", "REQUESTED", "REJECTED",
                    command.comment(), now);
            var detail = toDetail(commitmentMapper.selectByIdAndOrganization(
                    context.organizationId(), commitmentId),
                    commitmentMapper.selectApprovalCaseByCommitment(context.organizationId(),
                            commitmentId));
            audit.rejected(context.organizationId(), context.userId(), commitmentId,
                    budget.id(), approvalCase.id(), "REQUESTED", "REJECTED");
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    public CommitmentDetail cancel(AuthenticatedUser user, long commitmentId,
            CancelCommitmentCommand command, String idempotencyKey) {
        var context = authorizationContexts.fresh(user);
        requireRequestOrApproveGrant(context);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.cancelRequestHash(context.organizationId(),
                context.organizationMemberId(), commitmentId, command.expectedVersion());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(),
                    CommitmentIdempotency.OPERATION_COMMITMENT_CANCEL,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var budget = requireVisibleBudgetForCancel(context, commitmentId);
            var approveGranted = matchesBudgetScope(context, PERMISSION_COMMITMENT_APPROVE,
                    budget);
            var now = clock.instant();
            var commitmentLocked = commitmentMapper.selectByIdForUpdate(
                    context.organizationId(), commitmentId);
            if (commitmentLocked.version() != command.expectedVersion()) {
                throw staleVersion();
            }
            if (!commitmentLocked.status().canRejectOrCancel()) {
                throw stateConflict("Commitment cannot be canceled",
                        "Only a REQUESTED commitment can be canceled; the commitment is "
                                + commitmentLocked.status() + ".");
            }
            var approvalCase = requirePendingCase(context.organizationId(), commitmentId);
            if (!approveGranted) {
                var submitActor = commitmentMapper.selectSubmitActor(
                        context.organizationId(), approvalCase.id());
                if (submitActor == null || submitActor != context.organizationMemberId()) {
                    throw notFound("The commitment is not available to the current user.");
                }
            }
            if (commitmentMapper.updateCancel(context.organizationId(), commitmentId, now) != 1) {
                throw stateConflict("Commitment cancellation conflict",
                        "The commitment was no longer REQUESTED when the cancellation applied.");
            }
            commitmentMapper.updateApprovalCaseStatus(context.organizationId(),
                    approvalCase.id(), "PENDING", "CANCELED", now);
            commitmentMapper.insertApprovalAction(context.organizationId(), approvalCase.id(),
                    context.organizationMemberId(), "CANCEL", "REQUESTED", "CANCELED", null, now);
            var detail = toDetail(commitmentMapper.selectByIdAndOrganization(
                    context.organizationId(), commitmentId),
                    commitmentMapper.selectApprovalCaseByCommitment(context.organizationId(),
                            commitmentId));
            audit.canceled(context.organizationId(), context.userId(), commitmentId,
                    budget.id(), approvalCase.id(), "REQUESTED", "CANCELED");
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    public CommitmentDetail release(AuthenticatedUser user, long commitmentId,
            ReleaseCommitmentCommand command, String idempotencyKey) {
        var context = authorizationContexts.fresh(user);
        requireAnyApplicableGrant(context, PERMISSION_COMMITMENT_RELEASE);
        idempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.releaseRequestHash(context.organizationId(),
                context.organizationMemberId(), commitmentId, command.expectedVersion());
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(),
                    CommitmentIdempotency.OPERATION_COMMITMENT_RELEASE,
                    idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseCodec.fromJson(decision.responseBody());
            }
            var budgetIdentity = requireVisibleBudget(context, commitmentId,
                    PERMISSION_COMMITMENT_RELEASE);
            var now = clock.instant();
            periodFence.lockOpenById(context.organizationId(), budgetIdentity.billingPeriodId());
            var budgetLocked = budgetMapper.selectByIdForUpdate(context.organizationId(),
                    budgetIdentity.id());
            if (budgetLocked == null || budgetLocked.status() != BudgetStatus.ACTIVE) {
                throw stateConflict("Budget is not active",
                        "The budget must be ACTIVE before releasing.");
            }
            var commitmentLocked = commitmentMapper.selectByIdForUpdate(
                    context.organizationId(), commitmentId);
            if (commitmentLocked.version() != command.expectedVersion()) {
                throw staleVersion();
            }
            if (!commitmentLocked.status().canRelease()) {
                throw stateConflict("Commitment cannot be released",
                        "Only an ACTIVE or PARTIALLY_CONSUMED commitment can be released; the commitment is "
                                + commitmentLocked.status() + ".");
            }
            var remainder = commitmentLocked.remainingAmount();
            if (budgetMapper.decrementCommitted(context.organizationId(), budgetLocked.id(),
                    remainder, now) != 1) {
                throw stateConflict("Budget release conflict",
                        "The committed counter cannot cover the released remainder.");
            }
            if (commitmentMapper.updateRelease(context.organizationId(), commitmentId, now) != 1) {
                throw stateConflict("Commitment release conflict",
                        "The commitment was no longer releasable when the release applied.");
            }
            var approvalCase = commitmentMapper.selectApprovalCaseByCommitment(
                    context.organizationId(), commitmentId);
            var detail = toDetail(commitmentMapper.selectByIdAndOrganization(
                    context.organizationId(), commitmentId), approvalCase);
            audit.released(context.organizationId(), context.userId(), commitmentId,
                    budgetLocked.id(), remainder,
                    approvalCase == null ? 0 : approvalCase.id(),
                    commitmentLocked.status().name(), "RELEASED");
            idempotency.finalize(decision.id(), 200, responseCodec.toJson(detail));
            return detail;
        }));
    }

    protected CommitmentDetail toDetail(BudgetCommitment commitment,
            com.aicostops.budget.domain.CommitmentApprovalCase approvalCase) {
        var history = approvalCase == null
                ? List.<com.aicostops.budget.domain.CommitmentApprovalAction>of()
                : commitmentMapper.selectApprovalActionsByCase(commitment.organizationId(),
                        approvalCase.id());
        return CommitmentDetail.from(commitment, approvalCase, history);
    }

    protected static void requireAnyApplicableGrant(AuthorizationContext context,
            String permissionCode) {
        if (!hasApplicableGrant(context, permissionCode)) {
            throw forbidden();
        }
    }

    private static void requireRequestOrApproveGrant(AuthorizationContext context) {
        if (!hasApplicableGrant(context, PERMISSION_COMMITMENT_REQUEST)
                && !hasApplicableGrant(context, PERMISSION_COMMITMENT_APPROVE)) {
            throw forbidden();
        }
    }

    private static boolean hasApplicableGrant(AuthorizationContext context,
            String permissionCode) {
        var applicableScopes = M1AdminPermissionPolicy.applicableScopes(permissionCode);
        return context.grants().stream().anyMatch(grant ->
                grant.permissionCode().equals(permissionCode)
                        && applicableScopes.contains(grant.scopeType()));
    }

    private static DomainException forbidden() {
        return new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                "Permission is required",
                "The required permission is not granted at an applicable scope.");
    }

    protected Budget requireVisibleBudget(AuthorizationContext context, long commitmentId,
            String permissionCode) {
        var commitment = commitmentMapper.selectByIdAndOrganization(context.organizationId(),
                commitmentId);
        if (commitment == null) {
            throw notFound("The commitment is not available in the current organization.");
        }
        var budget = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                commitment.budgetId());
        if (budget == null) {
            throw notFound("The budget is not available in the current organization.");
        }
        authorization.requireResource(context, permissionCode,
                budget.scopeType(), budget.scopeId());
        return budget;
    }

    private Budget requireVisibleBudgetForCancel(AuthorizationContext context,
            long commitmentId) {
        var commitment = commitmentMapper.selectByIdAndOrganization(context.organizationId(),
                commitmentId);
        if (commitment == null) {
            throw notFound("The commitment is not available in the current organization.");
        }
        var budget = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                commitment.budgetId());
        if (budget == null) {
            throw notFound("The budget is not available in the current organization.");
        }
        var requestGranted = matchesBudgetScope(context, PERMISSION_COMMITMENT_REQUEST, budget);
        var approveGranted = matchesBudgetScope(context, PERMISSION_COMMITMENT_APPROVE, budget);
        if (!requestGranted && !approveGranted) {
            throw notFound("The commitment is not available in the current organization.");
        }
        return budget;
    }

    private static boolean matchesBudgetScope(AuthorizationContext context,
            String permissionCode, Budget budget) {
        var applicableScopes = M1AdminPermissionPolicy.applicableScopes(permissionCode);
        return context.grants().stream().anyMatch(grant ->
                grant.permissionCode().equals(permissionCode)
                        && applicableScopes.contains(grant.scopeType())
                        && matchesResource(context, grant, budget));
    }

    private static boolean matchesResource(AuthorizationContext context,
            ScopedPermissionGrant grant, Budget budget) {
        var organizationGrantMatches = grant.scopeType() == ScopeType.ORG
                && grant.scopeId() == context.organizationId()
                && (budget.scopeType() != ScopeType.ORG
                        || budget.scopeId() == context.organizationId());
        var typedGrantMatches = grant.scopeType() == budget.scopeType()
                && grant.scopeId() == budget.scopeId();
        return organizationGrantMatches || typedGrantMatches;
    }

    private com.aicostops.budget.domain.CommitmentApprovalCase requirePendingCase(
            long organizationId, long commitmentId) {
        var approvalCase = commitmentMapper.selectApprovalCaseByCommitmentForUpdate(
                organizationId, commitmentId);
        if (approvalCase == null
                || approvalCase.status() != CommitmentApprovalCaseStatus.PENDING) {
            throw stateConflict("Approval case conflict",
                    "The commitment's approval case must be PENDING for this transition.");
        }
        return approvalCase;
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
