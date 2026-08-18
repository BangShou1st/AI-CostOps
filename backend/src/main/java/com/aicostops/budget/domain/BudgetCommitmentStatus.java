package com.aicostops.budget.domain;

/**
 * Frozen BudgetCommitment lifecycle (03-state-machines.md):
 *
 * <pre>
 * REQUESTED → ACTIVE → PARTIALLY_CONSUMED → CONSUMED
 * ACTIVE / PARTIALLY_CONSUMED → RELEASED
 * REQUESTED → REJECTED / CANCELED
 * </pre>
 *
 * <p>No invented states: DRAFT / APPROVING / RESERVED / EXPIRED / CLOSED /
 * VOIDED do not exist. {@code REQUESTED} never holds budget capacity;
 * {@code ACTIVE} is the only state that occupies committed_amount.
 */
public enum BudgetCommitmentStatus {
    REQUESTED,
    ACTIVE,
    PARTIALLY_CONSUMED,
    CONSUMED,
    RELEASED,
    REJECTED,
    CANCELED;

    /** Only a REQUESTED commitment can be activated (atomic approval). */
    public boolean canActivate() {
        return this == REQUESTED;
    }

    /** Reject and cancel are exits of the REQUESTED state only. */
    public boolean canRejectOrCancel() {
        return this == REQUESTED;
    }

    /** Release frees the outstanding remainder of an active commitment. */
    public boolean canRelease() {
        return this == ACTIVE || this == PARTIALLY_CONSUMED;
    }

    /** Consume applies to active commitments with remaining capacity. */
    public boolean canConsume() {
        return this == ACTIVE || this == PARTIALLY_CONSUMED;
    }
}
