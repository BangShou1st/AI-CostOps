package com.aicostops.budget.domain;

/**
 * Frozen budget status foundation. Only {@code ACTIVE} exists: the AIC-044
 * Atomic Activation UPDATE requires {@code status='ACTIVE'}, and no other
 * budget status is frozen yet. Any future status needs an explicit,
 * forward-only migration.
 */
public enum BudgetStatus {
    ACTIVE
}