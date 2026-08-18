package com.aicostops.budget.application;

import java.math.BigDecimal;

/**
 * Audit port of the commitment lifecycle. Implementations must append the
 * audit event inside the caller's transaction so any audit write failure
 * rolls the whole command back. Metadata stays secret-free and minimal
 * (ids + statuses + amounts, never request payloads or keys).
 */
public interface CommitmentAuditPort {

    /** Appends {@code COMMITMENT_REQUESTED} (subject = commitment). */
    void requested(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal requestedAmount);

    /** Appends {@code COMMITMENT_ACTIVATED} with the transition and amount. */
    void activated(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal approvedAmount, long approvalCaseId,
            String fromStatus, String toStatus);

    /** Appends {@code COMMITMENT_REJECTED} with the transition. */
    void rejected(long organizationId, long actorUserId, long commitmentId,
            long budgetId, long approvalCaseId, String fromStatus, String toStatus);

    /** Appends {@code COMMITMENT_CANCELED} with the transition. */
    void canceled(long organizationId, long actorUserId, long commitmentId,
            long budgetId, long approvalCaseId, String fromStatus, String toStatus);

    /** Appends {@code COMMITMENT_RELEASED} with the released remainder. */
    void released(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal releasedAmount, long approvalCaseId,
            String fromStatus, String toStatus);

    /** Appends {@code COMMITMENT_CONSUMED} with the consumed amount. */
    void consumed(long organizationId, long actorUserId, long commitmentId,
            long budgetId, BigDecimal consumedAmount, long ledgerEntryId,
            String fromStatus, String toStatus);
}
