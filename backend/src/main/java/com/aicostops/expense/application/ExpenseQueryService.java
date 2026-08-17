package com.aicostops.expense.application;

import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense reads. Authorization runs in the HTTP layer before any resource
 * lookup (403 for a missing grant, privacy-preserving 404 otherwise); the OWN
 * guard compares the claimant against the current organization member.
 */
@Service
public class ExpenseQueryService {

    private static final String PERMISSION_EXPENSE_READ_OWN = "EXPENSE_READ_OWN";
    private static final String PERMISSION_EXPENSE_REVIEW = "EXPENSE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpenseClaimMapper mapper;

    public ExpenseQueryService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ExpenseReadModels.ExpenseDetail> listMine(
            AuthenticatedUser user, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_READ_OWN);
        var offset = Math.max(page, 0) * Math.max(size, 1);
        return mapper.selectByClaimant(context.organizationId(), context.organizationMemberId(),
                        Math.max(size, 1), offset).stream()
                .map(claim -> toDetail(context.organizationId(), claim))
                .toList();
    }

    public long countMine(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_READ_OWN);
        return mapper.countByClaimant(context.organizationId(), context.organizationMemberId());
    }

    /**
     * Owner-scoped detail lookup. Non-owned or missing expenses are
     * indistinguishable (privacy 404).
     */
    @Transactional(readOnly = true)
    public ExpenseReadModels.ExpenseDetail getOwned(AuthenticatedUser user, long expenseId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_READ_OWN);
        var claim = mapper.selectByIdAndOrganization(context.organizationId(), expenseId);
        if (claim == null || !claim.isOwnedBy(context.organizationMemberId())) {
            throw notFound();
        }
        return toDetail(context.organizationId(), claim);
    }

    /**
     * Org-scoped detail lookup for finance review (no owner comparison).
     * Missing expenses still 404.
     */
    @Transactional(readOnly = true)
    public ExpenseReadModels.ExpenseDetail getForReview(AuthenticatedUser user, long expenseId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        var claim = mapper.selectByIdAndOrganization(context.organizationId(), expenseId);
        if (claim == null) {
            throw notFound();
        }
        return toDetail(context.organizationId(), claim);
    }

    private ExpenseReadModels.ExpenseDetail toDetail(long organizationId, ExpenseClaim claim) {
        var approvalStatus = claim.approvalCaseId() == null ? null
                : mapper.selectApprovalCaseByExpense(organizationId, claim.id()).status();
        var decisionConfirmed = claim.currentAllocationDecisionId() == null ? false
                : "CONFIRMED".equals(mapper.selectDecisionStatus(
                        organizationId, claim.currentAllocationDecisionId()));
        var history = claim.approvalCaseId() == null ? List.<ApprovalAction>of()
                : mapper.selectApprovalActionsByCase(organizationId, claim.approvalCaseId());
        return ExpenseReadModels.ExpenseDetail.from(claim, approvalStatus, decisionConfirmed, history);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Expense not found",
                "The expense is not available to the current user.");
    }
}
