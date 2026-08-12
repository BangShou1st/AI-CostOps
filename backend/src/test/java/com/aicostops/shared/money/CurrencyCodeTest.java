package com.aicostops.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CurrencyCodeTest {

    @Test
    void normalizesLowercaseCurrencyCode() {
        assertThat(CurrencyCode.of("cny").value()).isEqualTo("CNY");
    }

    @Test
    void rejectsCodesThatAreNotThreeAsciiLetters() {
        assertThatIllegalArgumentException().isThrownBy(() -> CurrencyCode.of("CN"));
        assertThatIllegalArgumentException().isThrownBy(() -> CurrencyCode.of("12A"));
        assertThatIllegalArgumentException().isThrownBy(() -> CurrencyCode.of(null));
    }
}
