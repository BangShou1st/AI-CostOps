package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.expense.infrastructure.ExpenseApprovalMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds a detail from an already authorized claim; list queries use summaries instead. */
@Component
public class ExpenseDetailAssembler {
    private final ExpenseClaimMapper mapper;
    private final ExpenseApprovalMapper approvals;

    public ExpenseDetailAssembler(ExpenseClaimMapper mapper, ExpenseApprovalMapper approvals) {
        this.mapper = mapper;
        this.approvals = approvals;
    }

    public ExpenseDetail assemble(ExpenseClaim claim) {
        var organizationId = claim.organizationId();
        var approvalStatus = claim.approvalCaseId() == null ? null
                : approvals.selectApprovalCaseByExpense(organizationId, claim.id()).status();
        var decisionConfirmed = claim.currentAllocationDecisionId() != null
                && "CONFIRMED".equals(mapper.selectDecisionStatus(
                        organizationId, claim.currentAllocationDecisionId()));
        var history = claim.approvalCaseId() == null ? List.<ApprovalAction>of()
                : approvals.selectApprovalActionsByCase(organizationId, claim.approvalCaseId());
        return ExpenseDetail.from(claim, approvalStatus, decisionConfirmed, history);
    }
}
