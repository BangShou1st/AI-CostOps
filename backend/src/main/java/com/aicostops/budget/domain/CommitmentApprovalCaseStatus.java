package com.aicostops.budget.domain;

/**
 * Approval case statuses shared with the expense approval shell
 * (approval_case.status CHECK in V10/V12). This module keeps its own view of
 * the shared table so the budget module never depends on the expense module.
 */
public enum CommitmentApprovalCaseStatus {
    PENDING,
    NEEDS_INFO,
    APPROVED,
    REJECTED,
    CANCELED;

    public boolean canTransitionTo(CommitmentApprovalCaseStatus target) {
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
