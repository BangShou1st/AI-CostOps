package com.aicostops.expense.api;

import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Expense API responses. Money is a plain decimal string (8 fractional
 * digits); ids are strings; postingReady is derived server-side from DB truth.
 */
public final class ExpenseResponses {

    private ExpenseResponses() {
    }

    public record ExpenseResponse(
            ApiId id,
            String status,
            ApiId claimantMemberId,
            ApiId evidenceId,
            LocalDate expenseDate,
            String amount,
            String currency,
            ApiId currentAllocationDecisionId,
            ApiId approvalCaseId,
            String approvalStatus,
            boolean postingReady,
            boolean canEdit,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<ApprovalActionResponse> history) {

        public static ExpenseResponse from(ExpenseDetail detail, long viewerMemberId) {
            var canEdit = detail.status().editableByOwner()
                    && detail.claimantMemberId() == viewerMemberId;
            return new ExpenseResponse(
                    ApiId.of(detail.id()),
                    detail.status().name(),
                    ApiId.of(detail.claimantMemberId()),
                    detail.evidenceId() == null ? null : ApiId.of(detail.evidenceId()),
                    detail.expenseDate(),
                    detail.amount().toPlainString(),
                    detail.currency(),
                    detail.currentAllocationDecisionId() == null
                            ? null
                            : ApiId.of(detail.currentAllocationDecisionId()),
                    detail.approvalCaseId() == null ? null : ApiId.of(detail.approvalCaseId()),
                    detail.approvalStatus() == null ? null : detail.approvalStatus().name(),
                    detail.postingReady(),
                    canEdit,
                    detail.version(),
                    detail.createdAt(),
                    detail.updatedAt(),
                    detail.history().stream().map(ApprovalActionResponse::from).toList());
        }
    }

    public record ExpenseSummaryResponse(
            ApiId id,
            String status,
            ApiId evidenceId,
            LocalDate expenseDate,
            String amount,
            String currency,
            String approvalStatus,
            boolean postingReady,
            long version,
            Instant createdAt) {

        public static ExpenseSummaryResponse from(ExpenseDetail detail) {
            return new ExpenseSummaryResponse(
                    ApiId.of(detail.id()),
                    detail.status().name(),
                    detail.evidenceId() == null ? null : ApiId.of(detail.evidenceId()),
                    detail.expenseDate(),
                    detail.amount().toPlainString(),
                    detail.currency(),
                    detail.approvalStatus() == null ? null : detail.approvalStatus().name(),
                    detail.postingReady(),
                    detail.version(),
                    detail.createdAt());
        }
    }

    public record ApprovalActionResponse(
            ApiId id,
            String actionType,
            ApiId actorMemberId,
            String fromState,
            String toState,
            String comment,
            Instant createdAt) {

        public static ApprovalActionResponse from(
                com.aicostops.expense.domain.ApprovalAction action) {
            return new ApprovalActionResponse(
                    ApiId.of(action.id()),
                    action.actionType().name(),
                    ApiId.of(action.actorMemberId()),
                    action.fromState(),
                    action.toState(),
                    action.comment(),
                    action.createdAt());
        }
    }
}
