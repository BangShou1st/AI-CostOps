package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProviderNumberParserTest {

    @Test
    void decimalParsesPlainValuesExactly() {
        var parsed = ProviderNumberParser.decimal("12.50");

        assertThat(parsed.present()).isTrue();
        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.value()).isEqualByComparingTo(new BigDecimal("12.50"));
    }

    @Test
    void decimalTrimsSurroundingWhitespace() {
        assertThat(ProviderNumberParser.decimal("  7  ").value()).isEqualByComparingTo("7");
    }

    @Test
    void decimalZeroIsPresentAndValid() {
        var parsed = ProviderNumberParser.decimal("0");

        assertThat(parsed.present()).isTrue();
        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void decimalMissingIsNotPresent() {
        for (var missing : new String[] {null, "", "   "}) {
            var parsed = ProviderNumberParser.decimal(missing);
            assertThat(parsed.present()).isFalse();
            assertThat(parsed.valid()).isTrue();
            assertThat(parsed.value()).isNull();
        }
    }

    @Test
    void decimalMalformedIsPresentButInvalid() {
        for (var malformed : new String[] {"abc", "1,234.56", "1.2.3", "--5"}) {
            var parsed = ProviderNumberParser.decimal(malformed);
            assertThat(parsed.present()).isTrue();
            assertThat(parsed.valid()).isFalse();
            assertThat(parsed.value()).isNull();
        }
    }

    @Test
    void longValueParsesIntegralTokens() {
        var parsed = ProviderNumberParser.longValue("12345");

        assertThat(parsed.present()).isTrue();
        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.value()).isEqualTo(12345L);
    }

    @Test
    void longValueRejectsFractionsAndMalformedText() {
        assertThat(ProviderNumberParser.longValue("1.5").valid()).isFalse();
        assertThat(ProviderNumberParser.longValue("abc").valid()).isFalse();
        assertThat(ProviderNumberParser.longValue("").present()).isFalse();
        assertThat(ProviderNumberParser.longValue(null).present()).isFalse();
    }

    @Test
    void longValueZeroIsPresentAndValid() {
        assertThat(ProviderNumberParser.longValue("0").value()).isZero();
    }
}
