package com.aicostops.expense.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Expense command payloads. Money is always an exact {@link BigDecimal}
 * already validated by the API layer (or the caller) against the 8-decimal
 * policy; expectedVersion is the optimistic-lock counter carried by every
 * state mutation.
 */
public final class ExpenseCommands {

    private ExpenseCommands() {
    }

    public record CreateExpenseCommand(
            LocalDate expenseDate,
            BigDecimal amount,
            String currency) {
    }

    public record EditExpenseCommand(
            LocalDate expenseDate,
            BigDecimal amount,
            String currency,
            long expectedVersion) {
    }

    public record SubmitExpenseCommand(long expectedVersion) {
    }

    public record CancelExpenseCommand(long expectedVersion) {
    }

    public record RequestInfoCommand(long expectedVersion, String comment) {
    }

    public record ApproveExpenseCommand(long expectedVersion) {
    }

    public record RejectExpenseCommand(long expectedVersion, String comment) {
    }
}