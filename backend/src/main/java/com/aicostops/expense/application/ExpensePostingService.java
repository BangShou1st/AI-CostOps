package com.aicostops.expense.application;

import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Adapter/service that owns the ExpenseClaim posting state transition. */
@Service
public class ExpensePostingService implements ExpensePostingPort {

    private final ExpenseClaimMapper mapper;

    public ExpensePostingService(ExpenseClaimMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ExpensePostingSource load(long organizationId, long expenseId) {
        return toSource(requireClaim(organizationId, expenseId));
    }

    @Override
    public ExpensePostingSource lockAndRequireApproved(
            long organizationId, long expenseId, long expectedDecisionId) {
        var claim = mapper.selectByIdForUpdate(organizationId, expenseId);
        if (claim == null) {
            throw notFound();
        }
        if (claim.status() != ExpenseClaimStatus.APPROVED) {
            throw notEligible("Only APPROVED expenses can be posted.");
        }
        if (claim.currentAllocationDecisionId() == null
                || claim.currentAllocationDecisionId() != expectedDecisionId) {
            throw notEligible("The expense allocation pointer does not match the posting decision.");
        }
        return toSource(claim);
    }

    @Override
    public void markPosted(long organizationId, long expenseId, long expectedVersion, Instant now) {
        if (mapper.markPosted(organizationId, expenseId, expectedVersion, now) != 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Expense posting conflict",
                    "The expense was changed or is no longer APPROVED; reload and retry.");
        }
    }

    private ExpenseClaim requireClaim(long organizationId, long expenseId) {
        var claim = mapper.selectByIdAndOrganization(organizationId, expenseId);
        if (claim == null) {
            throw notFound();
        }
        return claim;
    }

    private static ExpensePostingSource toSource(ExpenseClaim claim) {
        return new ExpensePostingSource(claim.id(), claim.amount(), claim.currency(),
                claim.expenseDate(), claim.currentAllocationDecisionId(), claim.version(),
                claim.status());
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Expense not found", "The expense is not available in the current organization.");
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Expense is not postable", detail);
    }
}
