package com.aicostops.expense.domain;

/**
 * Append-only approval history events, written exactly once per state
 * mutation and never updated or deleted.
 */
public enum ApprovalActionType {
    SUBMIT,
    REQUEST_INFO,
    RESUBMIT,
    APPROVE,
    REJECT,
    CANCEL
}
