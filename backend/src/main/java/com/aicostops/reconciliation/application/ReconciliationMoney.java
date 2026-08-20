package com.aicostops.reconciliation.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact DECIMAL(20,8) validation for reconciliation money/tolerance. */
public final class ReconciliationMoney {

    private ReconciliationMoney() {
    }

    public static BigDecimal requireScale8Exact(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("money is required");
        }
        final BigDecimal scaled;
        try {
            scaled = value.setScale(8, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("money must be exactly representable at scale 8", ex);
        }
        if (scaled.precision() > 20) {
            throw new IllegalArgumentException("money exceeds DECIMAL(20,8)");
        }
        return scaled;
    }

    public static String format(BigDecimal value) {
        return requireScale8Exact(value).toPlainString();
    }
}
