package com.aicostops.expense.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact-representability guard for expense money, identical in rule to
 * AllocationDecimal: the value must fit {@code DECIMAL(20,8)} without any
 * rounding or truncation. Expense keeps its own thin copy instead of reaching
 * into the allocation module.
 */
public final class ExpenseMoneyPolicy {

    private static final int SCALE = 8;
    private static final int PRECISION = 20;

    private ExpenseMoneyPolicy() {
    }

    /** Returns the canonical scale-8 representation or throws. */
    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Expense amount is required");
        }
        BigDecimal canonical;
        try {
            canonical = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    "Expense amount must be exactly representable with " + SCALE + " decimals",
                    notExactlyRepresentable);
        }
        if (canonical.precision() > PRECISION) {
            throw new IllegalArgumentException(
                    "Expense amount must fit DECIMAL(" + PRECISION + "," + SCALE + ")");
        }
        return canonical;
    }
}
