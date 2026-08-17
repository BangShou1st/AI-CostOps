package com.aicostops.expense.domain;

/**
 * One approval case per expense, created on the first SUBMIT and reused across
 * REQUEST_INFO -> RESUBMIT cycles.
 */
public enum ApprovalCaseStatus {
    PENDING,
    NEEDS_INFO,
    APPROVED,
    REJECTED,
    CANCELED;

    public boolean canTransitionTo(ApprovalCaseStatus target) {
        return switch (this) {
            case PENDING -> target == NEEDS_INFO
                    || target == APPROVED
                    || target == REJECTED
                    || target == CANCELED;
            case NEEDS_INFO -> target == PENDING;
            case APPROVED, REJECTED, CANCELED -> false;
        };
    }
}
