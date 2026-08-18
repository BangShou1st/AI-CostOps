package com.aicostops.budget.domain;

import java.time.Instant;

/**
 * One immutable approval-history entry of a commitment case.
 * {@code fromState}/{@code toState} carry the commitment statuses around the
 * transition (e.g. REQUESTED → ACTIVE); {@code comment} carries the
 * REJECT reason.
 */
public record CommitmentApprovalAction(
        long id,
        long organizationId,
        long approvalCaseId,
        long actorMemberId,
        CommitmentApprovalActionType actionType,
        String fromState,
        String toState,
        String comment,
        Instant createdAt) {
}
