package com.aicostops.allocation.infrastructure;

import com.aicostops.allocation.application.AllocationSubjectPort;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.expense.application.ExpenseAllocationSourcePort;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * EXPENSE_CLAIM subject adapter: locks the expense claim, enforces the
 * APPROVED-status gate (no import lineage, no review status — expenses have no
 * provider lineage), and writes the expense current-decision pointer.
 */
@Component
public class ExpenseAllocationSubjectAdapter implements AllocationSubjectPort {

    private final ExpenseAllocationSourcePort expenseSource;

    public ExpenseAllocationSubjectAdapter(ExpenseAllocationSourcePort expenseSource) {
        this.expenseSource = expenseSource;
    }

    @Override
    public AllocationSubjectType subjectType() {
        return AllocationSubjectType.EXPENSE_CLAIM;
    }

    @Override
    public SubjectLoad loadForUpdate(long organizationId, long subjectId) {
        var subject = expenseSource.loadForUpdate(organizationId, subjectId);
        return new SubjectLoad(
                subject.expenseId(),
                subject.amount(),
                subject.currency(),
                subject.currentAllocationDecisionId(),
                subject.status());
    }

    @Override
    public void assertConfirmEligible(long organizationId, SubjectLoad load) {
        if (!"APPROVED".equals(load.status())) {
            throw notEligible(
                    "Only APPROVED expenses are eligible for allocation confirm.");
        }
        if (load.currentAllocationDecisionId() != null) {
            throw alreadyConfirmed();
        }
    }

    @Override
    public void setCurrentDecisionPointer(long organizationId, long subjectId, long decisionId) {
        expenseSource.attachAllocationPointer(organizationId, subjectId, decisionId);
    }

    private static DomainException alreadyConfirmed() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_ALREADY_CONFIRMED,
                "Allocation already confirmed",
                "The expense already has a confirmed allocation that cannot be rewritten.");
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Expense not eligible for allocation",
                detail);
    }
}