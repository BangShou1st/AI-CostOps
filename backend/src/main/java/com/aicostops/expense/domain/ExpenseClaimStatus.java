package com.aicostops.expense.domain;

/** Expense lifecycle state machine; POSTED is terminal and there is no VOIDED state. */
public enum ExpenseClaimStatus {
    DRAFT,
    SUBMITTED,
    NEEDS_INFO,
    APPROVED,
    POSTED,
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
            case APPROVED -> target == POSTED;
            case POSTED, REJECTED, CANCELED -> false;
        };
    }

    /** The statuses an owner may edit the expense body in. */
    public boolean editableByOwner() {
        return this == DRAFT || this == NEEDS_INFO;
    }
}
