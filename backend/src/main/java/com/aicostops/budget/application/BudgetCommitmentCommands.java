package com.aicostops.budget.application;

import java.math.BigDecimal;

/** Budget commitment commands (AIC-044 request / AIC-045 lifecycle). */
public final class BudgetCommitmentCommands {

    private BudgetCommitmentCommands() {
    }

    /**
     * A new commitment request. The caller can never pass status or
     * approved/remaining amounts: the request always produces
     * REQUESTED + NULL approved/remaining, and activation decides amounts.
     * {@code currency} must match the budget currency.
     */
    public record RequestCommitmentCommand(
            long budgetId,
            BigDecimal requestedAmount,
            String currency) {
    }

    /** Activation approval with the commitment's optimistic version CAS. */
    public record ApproveCommitmentCommand(
            long expectedVersion) {
    }

    /** Reviewer rejection (REQUESTED only) with the reason. */
    public record RejectCommitmentCommand(
            long expectedVersion,
            String comment) {
    }

    /** Requester/reviewer cancellation (REQUESTED only). */
    public record CancelCommitmentCommand(
            long expectedVersion) {
    }

    /** Release of the outstanding remainder (ACTIVE / PARTIALLY_CONSUMED). */
    public record ReleaseCommitmentCommand(
            long expectedVersion) {
    }
}
