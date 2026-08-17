package com.aicostops.expense.application;

import java.math.BigDecimal;

/**
 * Allocation workflow access to the expense source: a locked read of the
 * claim's allocation facts and the pointer mutation that records the confirmed
 * decision. Implemented by the expense infrastructure so the allocation module
 * never touches expense persistence directly.
 */
public interface ExpenseAllocationSourcePort {

    /** Locks the expense row and returns its allocation-relevant facts. */
    ExpenseSubject loadForUpdate(long organizationId, long expenseId);

    /** Org-scoped existence check (read path; never locks). */
    boolean exists(long organizationId, long expenseId);

    /** Writes {@code current_allocation_decision_id} (confirm transaction only). */
    void attachAllocationPointer(long organizationId, long expenseId, long decisionId);

    record ExpenseSubject(
            long expenseId,
            long organizationId,
            BigDecimal amount,
            String currency,
            String status,
            Long currentAllocationDecisionId) {
    }
}