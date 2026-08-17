package com.aicostops.expense.application;

import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ApprovalCaseStatus;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read models of the expense module. Money is kept as {@link BigDecimal} at
 * the application boundary and stringified only at the API edge.
 */
public final class ExpenseReadModels {

    private ExpenseReadModels() {
    }

    /**
     * Expense plus the derived approval/allocation facts: approval case
     * status, whether the current allocation decision is CONFIRMED (DB truth,
     * never inferred by the client), and the derived postingReady flag.
     */
    public record ExpenseDetail(
            long id,
            long organizationId,
            long claimantMemberId,
            Long evidenceId,
            LocalDate expenseDate,
            BigDecimal amount,
            String currency,
            ExpenseClaimStatus status,
            Long currentAllocationDecisionId,
            Long approvalCaseId,
            ApprovalCaseStatus approvalStatus,
            boolean decisionConfirmed,
            boolean postingReady,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<ApprovalAction> history) {

        public static ExpenseDetail from(ExpenseClaim claim, ApprovalCaseStatus approvalStatus,
                boolean decisionConfirmed, List<ApprovalAction> history) {
            var postingReady = claim.status() == ExpenseClaimStatus.APPROVED
                    && claim.currentAllocationDecisionId() != null
                    && decisionConfirmed;
            return new ExpenseDetail(
                    claim.id(), claim.organizationId(), claim.claimantMemberId(),
                    claim.evidenceId(), claim.expenseDate(), claim.amount(), claim.currency(),
                    claim.status(), claim.currentAllocationDecisionId(), claim.approvalCaseId(),
                    approvalStatus, decisionConfirmed, postingReady, claim.version(),
                    claim.createdAt(), claim.updatedAt(), history);
        }
    }

    public record ExpenseSummary(
            long id,
            long organizationId,
            long claimantMemberId,
            Long evidenceId,
            LocalDate expenseDate,
            BigDecimal amount,
            String currency,
            ExpenseClaimStatus status,
            ApprovalCaseStatus approvalStatus,
            boolean postingReady,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static ExpenseSummary from(ExpenseDetail detail) {
            return new ExpenseSummary(
                    detail.id(), detail.organizationId(), detail.claimantMemberId(),
                    detail.evidenceId(), detail.expenseDate(), detail.amount(), detail.currency(),
                    detail.status(), detail.approvalStatus(), detail.postingReady(),
                    detail.version(), detail.createdAt(), detail.updatedAt());
        }
    }
}
