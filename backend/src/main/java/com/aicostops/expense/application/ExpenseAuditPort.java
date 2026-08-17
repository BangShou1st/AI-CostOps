package com.aicostops.expense.application;

/**
 * Audit port of the expense workflow. Implementations must append the audit
 * event inside the caller's transaction so any audit write failure rolls the
 * whole command back.
 */
public interface ExpenseAuditPort {

    /** Appends {@code EXPENSE_CREATED} (subject = expense claim). */
    void claimCreated(long organizationId, long actorUserId, long expenseId,
            String currency);

    /** Appends {@code EXPENSE_EDITED} with the resulting version. */
    void claimEdited(long organizationId, long actorUserId, long expenseId,
            long resultingVersion, String currency);

    /** Appends {@code EXPENSE_SUBMITTED} / {@code EXPENSE_RESUBMITTED}. */
    void submitted(long organizationId, long actorUserId, long expenseId,
            String actionType, long resultingVersion);

    /** Appends {@code EXPENSE_CANCELED}. */
    void canceled(long organizationId, long actorUserId, long expenseId, long resultingVersion);

    /** Appends {@code EXPENSE_REVIEWED} with the review outcome. */
    void reviewed(long organizationId, long actorUserId, long expenseId, long resultingVersion,
            String actionType, String comment);

    /** Appends {@code EXPENSE_EVIDENCE_ATTACHED}. */
    void evidenceAttached(long organizationId, long actorUserId, long expenseId,
            long evidenceId, long resultingVersion);
}
