package com.aicostops.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void treatsEquivalentDecimalScalesAsTheSameAmount() {
        var cny = CurrencyCode.of("CNY");

        assertThat(Money.of(new BigDecimal("1.0"), cny))
                .isEqualTo(Money.of(new BigDecimal("1.00000000"), cny));
    }

    @Test
    void keepsDifferentCurrenciesDistinct() {
        var amount = new BigDecimal("1.00000000");

        assertThat(Money.of(amount, CurrencyCode.of("CNY")))
                .isNotEqualTo(Money.of(amount, CurrencyCode.of("USD")));
    }

    @Test
    void refusesToAddDifferentCurrencies() {
        var cny = Money.of(new BigDecimal("1.00"), CurrencyCode.of("CNY"));
        var usd = Money.of(new BigDecimal("1.00"), CurrencyCode.of("USD"));

        assertThatIllegalArgumentException().isThrownBy(() -> cny.add(usd));
    }

    @Test
    void addsAmountsWithoutFloatingPointConversion() {
        var cny = CurrencyCode.of("CNY");

        assertThat(Money.of(new BigDecimal("0.10"), cny)
                .add(Money.of(new BigDecimal("0.20"), cny)).amount())
                .isEqualByComparingTo("0.30");
    }
}
