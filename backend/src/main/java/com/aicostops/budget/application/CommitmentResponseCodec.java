package com.aicostops.budget.application;

import com.aicostops.budget.application.CommitmentReadModels.CommitmentDetail;
import com.aicostops.budget.domain.BudgetCommitmentStatus;
import com.aicostops.budget.domain.CommitmentApprovalAction;
import com.aicostops.budget.domain.CommitmentApprovalActionType;
import com.aicostops.budget.domain.CommitmentApprovalCaseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes the {@link CommitmentDetail} stored in the idempotency table,
 * so a replay returns the cached semantic response instead of re-reading
 * current commitment state that later commands may have changed. Money
 * travels as its plain decimal string because a generic JSON number
 * round-trip does not preserve the exact database scale.
 */
@Component
public final class CommitmentResponseCodec {

    private final ObjectMapper objectMapper;

    public CommitmentResponseCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(CommitmentDetail detail) {
        try {
            return objectMapper.writeValueAsString(StoredDetail.from(detail));
        } catch (Exception exception) {
            throw new IllegalStateException("Commitment detail serialization failed", exception);
        }
    }

    public CommitmentDetail fromJson(String json) {
        try {
            return objectMapper.readValue(json, StoredDetail.class).toDetail();
        } catch (Exception exception) {
            throw new IllegalStateException("Stored commitment detail is not valid", exception);
        }
    }

    private record StoredDetail(
            long id,
            long organizationId,
            long budgetId,
            String status,
            String requestedAmount,
            String approvedAmount,
            String remainingAmount,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Long approvalCaseId,
            String approvalStatus,
            List<StoredAction> history) {

        static StoredDetail from(CommitmentDetail detail) {
            return new StoredDetail(
                    detail.id(), detail.organizationId(), detail.budgetId(),
                    detail.status().name(), detail.requestedAmount().toPlainString(),
                    detail.approvedAmount() == null ? null : detail.approvedAmount().toPlainString(),
                    detail.remainingAmount() == null ? null : detail.remainingAmount().toPlainString(),
                    detail.version(), detail.createdAt(), detail.updatedAt(),
                    detail.approvalCaseId(),
                    detail.approvalStatus() == null ? null : detail.approvalStatus().name(),
                    detail.history().stream().map(StoredAction::from).toList());
        }

        CommitmentDetail toDetail() {
            return new CommitmentDetail(
                    id, organizationId, budgetId, BudgetCommitmentStatus.valueOf(status),
                    new BigDecimal(requestedAmount),
                    approvedAmount == null ? null : new BigDecimal(approvedAmount),
                    remainingAmount == null ? null : new BigDecimal(remainingAmount),
                    version, createdAt, updatedAt, approvalCaseId,
                    approvalStatus == null ? null : CommitmentApprovalCaseStatus.valueOf(approvalStatus),
                    history.stream().map(StoredAction::toAction).toList());
        }
    }

    private record StoredAction(
            long id,
            long organizationId,
            long approvalCaseId,
            long actorMemberId,
            String actionType,
            String fromState,
            String toState,
            String comment,
            Instant createdAt) {

        static StoredAction from(CommitmentApprovalAction action) {
            return new StoredAction(
                    action.id(), action.organizationId(), action.approvalCaseId(),
                    action.actorMemberId(), action.actionType().name(), action.fromState(),
                    action.toState(), action.comment(), action.createdAt());
        }

        CommitmentApprovalAction toAction() {
            return new CommitmentApprovalAction(
                    id, organizationId, approvalCaseId, actorMemberId,
                    CommitmentApprovalActionType.valueOf(actionType), fromState, toState,
                    comment, createdAt);
        }
    }
}
