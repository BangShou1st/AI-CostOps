package com.aicostops.expense.domain;

import java.time.Instant;

/**
 * One immutable approval-history entry. {@code fromState}/{@code toState} are
 * the expense claim statuses around the transition; {@code comment} carries
 * the REQUEST_INFO/REJECT reason.
 */
public record ApprovalAction(
        long id,
        long organizationId,
        long approvalCaseId,
        long actorMemberId,
        ApprovalActionType actionType,
        String fromState,
        String toState,
        String comment,
        Instant createdAt) {
}
