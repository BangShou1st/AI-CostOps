package com.aicostops.budget.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact-representability guard for budget money at the persistence boundary:
 * the value must fit {@code DECIMAL(20,8)} without any rounding or
 * truncation. Budget keeps its own thin copy of the shared rule instead of
 * reaching into the expense or allocation modules (same convention as
 * ExpenseMoneyPolicy / AllocationDecimal).
 */
public final class BudgetDecimal {

    private static final int SCALE = 8;
    private static final int PRECISION = 20;

    private BudgetDecimal() {
    }

    /** Returns the canonical scale-8 representation or throws. */
    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Budget amount is required");
        }
        BigDecimal canonical;
        try {
            canonical = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    "Budget amount must be exactly representable with " + SCALE + " decimals",
                    notExactlyRepresentable);
        }
        if (canonical.precision() > PRECISION) {
            throw new IllegalArgumentException(
                    "Budget amount must fit DECIMAL(" + PRECISION + "," + SCALE + ")");
        }
        return canonical;
    }
}