package com.aicostops.budget.application;

import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.BudgetCommitmentStatus;
import com.aicostops.budget.domain.CommitmentApprovalAction;
import com.aicostops.budget.domain.CommitmentApprovalCase;
import com.aicostops.budget.domain.CommitmentApprovalCaseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Commitment read models. */
public final class CommitmentReadModels {

    private CommitmentReadModels() {
    }

    public record CommitmentDetail(
            long id,
            long organizationId,
            long budgetId,
            BudgetCommitmentStatus status,
            BigDecimal requestedAmount,
            BigDecimal approvedAmount,
            BigDecimal remainingAmount,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Long approvalCaseId,
            CommitmentApprovalCaseStatus approvalStatus,
            List<CommitmentApprovalAction> history) {

        public static CommitmentDetail from(BudgetCommitment commitment,
                CommitmentApprovalCase approvalCase, List<CommitmentApprovalAction> history) {
            return new CommitmentDetail(
                    commitment.id(), commitment.organizationId(), commitment.budgetId(),
                    commitment.status(), commitment.requestedAmount(),
                    commitment.approvedAmount(), commitment.remainingAmount(),
                    commitment.version(), commitment.createdAt(), commitment.updatedAt(),
                    approvalCase == null ? null : approvalCase.id(),
                    approvalCase == null ? null : approvalCase.status(),
                    history);
        }
    }
}
