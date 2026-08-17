package com.aicostops.expense.infrastructure;

import com.aicostops.expense.application.ExpenseAllocationSourcePort;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Expense allocation source backed by {@link ExpenseClaimMapper}. */
@Component
public class MyBatisExpenseAllocationSource implements ExpenseAllocationSourcePort {

    private final ExpenseClaimMapper mapper;
    private final Clock clock;

    public MyBatisExpenseAllocationSource(ExpenseClaimMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public ExpenseSubject loadForUpdate(long organizationId, long expenseId) {
        var claim = mapper.selectByIdForUpdate(organizationId, expenseId);
        if (claim == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Expense not found",
                    "The expense is not available in the current organization.");
        }
        return new ExpenseSubject(
                claim.id(), claim.organizationId(), claim.amount(), claim.currency(),
                claim.status().name(), claim.currentAllocationDecisionId());
    }

    @Override
    public boolean exists(long organizationId, long expenseId) {
        return mapper.selectByIdAndOrganization(organizationId, expenseId) != null;
    }

    @Override
    public void attachAllocationPointer(long organizationId, long expenseId, long decisionId) {
        if (mapper.updateCurrentAllocationDecisionPointer(
                organizationId, expenseId, decisionId, clock.instant()) != 1) {
            throw new IllegalStateException(
                    "The expense current-decision pointer update must affect exactly one row");
        }
    }
}