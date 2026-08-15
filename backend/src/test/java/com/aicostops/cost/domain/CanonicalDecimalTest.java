package com.aicostops.cost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Exact representability guard for canonical DECIMAL columns. Currency validation
 * is a persistence-boundary concern and is intentionally not covered here.
 */
class CanonicalDecimalTest {

    @Test
    void moneyAcceptsExactEightFractionDigits() {
        var scaled = CanonicalDecimal.money(new BigDecimal("1.123456780"));

        assertThat(scaled).isEqualByComparingTo("1.12345678");
    }

    @Test
    void moneyFailsClosedWhenMoreThanEightFractionDigits() {
        assertThatThrownBy(() -> CanonicalDecimal.money(new BigDecimal("1.123456789")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not exactly representable at scale 8");
    }

    @Test
    void moneyFailsClosedOnPrecisionOverflow() {
        assertThatThrownBy(() -> CanonicalDecimal.money(new BigDecimal("100000000000000000000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit DECIMAL(20,8)");
    }

    @Test
    void usageFailsClosedOnNonZeroFractionBeyondScaleEight() {
        assertThatThrownBy(() -> CanonicalDecimal.usage(new BigDecimal("0.000000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not exactly representable at scale 8");
    }

    @Test
    void usageFailsClosedOnPrecisionOverflow() {
        assertThatThrownBy(() -> CanonicalDecimal.usage(new BigDecimal("1000000000000000000000000000000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit DECIMAL(30,8)");
    }
}
