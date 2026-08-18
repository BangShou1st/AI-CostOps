package com.aicostops.budget.domain;

/**
 * Approval action types shared with the expense approval shell
 * (approval_action.action_type CHECK in V10). Append-only: written exactly
 * once per state mutation and never updated or deleted.
 */
public enum CommitmentApprovalActionType {
    SUBMIT,
    REQUEST_INFO,
    RESUBMIT,
    APPROVE,
    REJECT,
    CANCEL
}
