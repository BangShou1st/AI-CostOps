package com.aicostops.budget.api;

import com.aicostops.budget.application.CommitmentReadModels.CommitmentDetail;
import com.aicostops.budget.domain.CommitmentApprovalAction;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;
import java.util.List;

/**
 * Commitment API responses. Money is a plain decimal string (8 fractional
 * digits), ids are strings, and the approval history is append-only.
 */
public final class CommitmentResponses {

    private CommitmentResponses() {
    }

    public record CommitmentResponse(
            ApiId id,
            ApiId budgetId,
            String status,
            String requestedAmount,
            String approvedAmount,
            String remainingAmount,
            long version,
            Instant createdAt,
            Instant updatedAt,
            ApiId approvalCaseId,
            String approvalStatus,
            List<ApprovalActionResponse> history) {

        public static CommitmentResponse from(CommitmentDetail detail) {
            return new CommitmentResponse(
                    ApiId.of(detail.id()),
                    ApiId.of(detail.budgetId()),
                    detail.status().name(),
                    detail.requestedAmount().toPlainString(),
                    detail.approvedAmount() == null ? null : detail.approvedAmount().toPlainString(),
                    detail.remainingAmount() == null ? null : detail.remainingAmount().toPlainString(),
                    detail.version(),
                    detail.createdAt(),
                    detail.updatedAt(),
                    detail.approvalCaseId() == null ? null : ApiId.of(detail.approvalCaseId()),
                    detail.approvalStatus() == null ? null : detail.approvalStatus().name(),
                    detail.history().stream().map(ApprovalActionResponse::from).toList());
        }
    }

    public record ApprovalActionResponse(
            long id,
            ApiId approvalCaseId,
            ApiId actorMemberId,
            String actionType,
            String fromState,
            String toState,
            String comment,
            Instant createdAt) {

        static ApprovalActionResponse from(CommitmentApprovalAction action) {
            return new ApprovalActionResponse(
                    action.id(),
                    ApiId.of(action.approvalCaseId()),
                    ApiId.of(action.actorMemberId()),
                    action.actionType().name(),
                    action.fromState(),
                    action.toState(),
                    action.comment(),
                    action.createdAt());
        }
    }
}
