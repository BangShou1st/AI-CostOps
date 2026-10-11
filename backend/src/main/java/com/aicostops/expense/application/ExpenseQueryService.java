package com.aicostops.expense.application;

import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.expense.infrastructure.ExpenseQueryMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import com.aicostops.shared.web.PageRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense reads. Authorization runs in this service before any resource
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
    private final ExpenseQueryMapper listQueries;
    private final ExpenseDetailAssembler details;

    public ExpenseQueryService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper mapper,
            ExpenseQueryMapper listQueries,
            ExpenseDetailAssembler details) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.listQueries = listQueries;
        this.details = details;
    }

    @Transactional(readOnly = true)
    public List<ExpenseReadModels.ExpenseSummary> listMine(
            AuthenticatedUser user, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_READ_OWN);
        var pagination = PageRequest.of(page, size);
        return listQueries.selectSummariesByClaimant(context.organizationId(),
                context.organizationMemberId(), pagination.size(), pagination.offset());
    }

    public long countMine(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_READ_OWN);
        return listQueries.countByClaimant(context.organizationId(), context.organizationMemberId());
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
        return details.assemble(claim);
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
        return details.assemble(claim);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Expense not found",
                "The expense is not available to the current user.");
    }
}
