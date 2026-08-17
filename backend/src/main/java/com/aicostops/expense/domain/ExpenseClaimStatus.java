package com.aicostops.expense.domain;

/**
 * Frozen M4 expense state machine. APPROVED is the terminal business state of
 * this milestone: there is no POSTED/VOIDED transition and a DRAFT or
 * NEEDS_INFO expense cannot be canceled.
 */
public enum ExpenseClaimStatus {
    DRAFT,
    SUBMITTED,
    NEEDS_INFO,
    APPROVED,
    REJECTED,
    CANCELED;

    public boolean canTransitionTo(ExpenseClaimStatus target) {
        return switch (this) {
            case DRAFT -> target == SUBMITTED;
            case SUBMITTED -> target == NEEDS_INFO
                    || target == APPROVED
                    || target == REJECTED
                    || target == CANCELED;
            case NEEDS_INFO -> target == SUBMITTED;
            case APPROVED, REJECTED, CANCELED -> false;
        };
    }

    /** The statuses an owner may edit the expense body in. */
    public boolean editableByOwner() {
        return this == DRAFT || this == NEEDS_INFO;
    }
}
