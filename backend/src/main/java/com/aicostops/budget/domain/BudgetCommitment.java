package com.aicostops.budget.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable budget commitment state. {@code requestedAmount} is fixed at
 * request time; {@code approvedAmount} / {@code remainingAmount} are NULL
 * until activation and are written exactly once by the atomic activation
 * transaction (no partial approval policy in V1: approved = requested).
 * {@code remainingAmount} never goes below zero and never exceeds
 * {@code approvedAmount}.
 */
public record BudgetCommitment(
        long id,
        long organizationId,
        long budgetId,
        BudgetCommitmentStatus status,
        BigDecimal requestedAmount,
        BigDecimal approvedAmount,
        BigDecimal remainingAmount,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
