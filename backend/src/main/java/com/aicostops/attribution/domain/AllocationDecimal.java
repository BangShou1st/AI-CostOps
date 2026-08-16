package com.aicostops.attribution.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact-representability guard for allocation money at the persistence
 * boundary: the value must fit {@code DECIMAL(20,8)} without any rounding or
 * truncation. Silent half-scale persistence is a bug, so excess scale and
 * precision overflow are rejected before the mapper ever runs.
 */
public final class AllocationDecimal {

    private static final int SCALE = 8;
    private static final int PRECISION = 20;

    private AllocationDecimal() {
    }

    /** Returns the canonical scale-8 representation or throws. */
    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Allocation amount is required");
        }
        BigDecimal canonical;
        try {
            canonical = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException notExactlyRepresentable) {
            throw new IllegalArgumentException(
                    "Allocation amount must be exactly representable with " + SCALE + " decimals",
                    notExactlyRepresentable);
        }
        if (canonical.precision() > PRECISION) {
            throw new IllegalArgumentException(
                    "Allocation amount must fit DECIMAL(" + PRECISION + "," + SCALE + ")");
        }
        return canonical;
    }
}
