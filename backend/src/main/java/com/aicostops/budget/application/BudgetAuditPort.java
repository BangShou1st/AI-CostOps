package com.aicostops.budget.application;

import java.math.BigDecimal;

/**
 * Audit port of the budget management workflow. Implementations must append
 * the audit event inside the caller's transaction so any audit write failure
 * rolls the whole command back. Metadata stays secret-free and minimal.
 */
public interface BudgetAuditPort {

    /** Appends {@code BUDGET_CREATED} (subject = budget). */
    void created(long organizationId, long actorUserId, long budgetId, String currency,
            String scopeType, long scopeId, BigDecimal totalAmount);

    /** Appends {@code BUDGET_TOTAL_CHANGED} with the resulting version. */
    void totalChanged(long organizationId, long actorUserId, long budgetId,
            long resultingVersion, BigDecimal totalAmount);
}