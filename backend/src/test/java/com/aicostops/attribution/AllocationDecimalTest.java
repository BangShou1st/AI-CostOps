package com.aicostops.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.attribution.domain.AllocationDecimal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AllocationDecimalTest {

    @Test
    void acceptsExactEightScaleMoney() {
        assertThat(AllocationDecimal.money(new BigDecimal("1.123456780")))
                .isEqualByComparingTo("1.12345678");
        assertThat(AllocationDecimal.money(new BigDecimal("1.123456780")).scale()).isEqualTo(8);
    }

    @Test
    void rejectsSubScalePrecisionLossBeforeTheMapper() {
        assertThatThrownBy(() -> AllocationDecimal.money(new BigDecimal("1.123456789")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDecimalColumnPrecisionOverflow() {
        // 15 integer digits + 8 fraction digits exceed DECIMAL(20,8).
        assertThatThrownBy(() -> AllocationDecimal.money(new BigDecimal("123456789012345.12345678")))
                .isInstanceOf(IllegalArgumentException.class);
        // 12 integer digits + 8 fraction digits fit exactly.
        assertThat(AllocationDecimal.money(new BigDecimal("123456789012.12345678")))
                .isEqualByComparingTo("123456789012.12345678");
    }

    @Test
    void acceptsNegativeAndZeroAmounts() {
        assertThat(AllocationDecimal.money(new BigDecimal("-5.00000000")))
                .isEqualByComparingTo("-5");
        assertThat(AllocationDecimal.money(new BigDecimal("0.00000000")))
                .isEqualByComparingTo("0");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> AllocationDecimal.money(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
