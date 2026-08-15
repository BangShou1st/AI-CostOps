package com.aicostops.cost.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact decimal representability guard for canonical financial persistence.
 *
 * <p>Canonical DECIMAL columns must never rely on MySQL silent rounding; provider
 * evidence is never rounded or truncated. A value that cannot be represented
 * exactly fails closed so the whole bounded transaction rolls back.
 */
public final class CanonicalDecimal {

    public static final int MONEY_PRECISION = 20;   // DECIMAL(20,8)
    public static final int USAGE_PRECISION = 30;   // DECIMAL(30,8)
    public static final int SCALE = 8;

    private CanonicalDecimal() {
    }

    /** Money DECIMAL(20,8): exact scale plus total digits &le; 20. */
    public static BigDecimal money(BigDecimal value) {
        return exact(value, MONEY_PRECISION);
    }

    /** Usage DECIMAL(30,8): exact scale plus total digits &le; 30. */
    public static BigDecimal usage(BigDecimal value) {
        return exact(value, USAGE_PRECISION);
    }

    private static BigDecimal exact(BigDecimal value, int maxPrecision) {
        try {
            var scaled = value.setScale(SCALE, RoundingMode.UNNECESSARY); // >8 digits or a rounding need -> throw
            if (scaled.precision() > maxPrecision) {
                throw new IllegalArgumentException("value does not fit DECIMAL("
                        + maxPrecision + ",8): " + value.toPlainString());
            }
            return scaled;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("value is not exactly representable at scale 8: "
                    + value.toPlainString(), e);
        }
    }
}
