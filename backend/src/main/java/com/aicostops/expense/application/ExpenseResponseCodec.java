package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.domain.ApprovalAction;
import com.aicostops.expense.domain.ApprovalActionType;
import com.aicostops.expense.domain.ApprovalCaseStatus;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes the {@link ExpenseDetail} stored in the idempotency table, so a
 * replay returns the cached semantic response instead of re-reading current
 * expense state that later commands may have changed. Money travels as its
 * plain decimal string because a generic JSON number round-trip does not
 * preserve the exact database scale.
 */
@Component
public final class ExpenseResponseCodec {

    private final ObjectMapper objectMapper;

    public ExpenseResponseCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(ExpenseDetail detail) {
        try {
            return objectMapper.writeValueAsString(StoredDetail.from(detail));
        } catch (Exception exception) {
            throw new IllegalStateException("Expense detail serialization failed", exception);
        }
    }

    public ExpenseDetail fromJson(String json) {
        try {
            return objectMapper.readValue(json, StoredDetail.class).toDetail();
        } catch (Exception exception) {
            throw new IllegalStateException("Stored expense detail is not valid", exception);
        }
    }

    private record StoredDetail(
            long id,
            long organizationId,
            long claimantMemberId,
            Long evidenceId,
            LocalDate expenseDate,
            String amount,
            String currency,
            String status,
            Long currentAllocationDecisionId,
            Long approvalCaseId,
            String approvalStatus,
            boolean decisionConfirmed,
            boolean postingReady,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<StoredAction> history) {

        static StoredDetail from(ExpenseDetail detail) {
            return new StoredDetail(
                    detail.id(), detail.organizationId(), detail.claimantMemberId(),
                    detail.evidenceId(), detail.expenseDate(), detail.amount().toPlainString(),
                    detail.currency(), detail.status().name(),
                    detail.currentAllocationDecisionId(), detail.approvalCaseId(),
                    detail.approvalStatus() == null ? null : detail.approvalStatus().name(),
                    detail.decisionConfirmed(), detail.postingReady(), detail.version(),
                    detail.createdAt(), detail.updatedAt(),
                    detail.history().stream().map(StoredAction::from).toList());
        }

        ExpenseDetail toDetail() {
            return new ExpenseDetail(
                    id, organizationId, claimantMemberId, evidenceId, expenseDate,
                    new BigDecimal(amount), currency, ExpenseClaimStatus.valueOf(status),
                    currentAllocationDecisionId, approvalCaseId,
                    approvalStatus == null ? null : ApprovalCaseStatus.valueOf(approvalStatus),
                    decisionConfirmed, postingReady, version, createdAt, updatedAt,
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

        static StoredAction from(ApprovalAction action) {
            return new StoredAction(
                    action.id(), action.organizationId(), action.approvalCaseId(),
                    action.actorMemberId(), action.actionType().name(), action.fromState(),
                    action.toState(), action.comment(), action.createdAt());
        }

        ApprovalAction toAction() {
            return new ApprovalAction(
                    id, organizationId, approvalCaseId, actorMemberId,
                    ApprovalActionType.valueOf(actionType), fromState, toState, comment, createdAt);
        }
    }
}
