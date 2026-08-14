package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderTimeParserTest {

    @Test
    void offsetInstantAcceptsUtcZulu() {
        assertThat(ProviderTimeParser.offsetInstant("2026-08-01T00:00:00Z"))
                .contains(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void offsetInstantConvertsExplicitOffsets() {
        assertThat(ProviderTimeParser.offsetInstant("2026-08-01T08:00:00+08:00"))
                .contains(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(ProviderTimeParser.offsetInstant("2026-07-31T16:00:00-08:00"))
                .contains(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void offsetInstantRejectsTimezoneLessDates() {
        assertThat(ProviderTimeParser.offsetInstant("2026-08-01T00:00:00")).isEmpty();
        assertThat(ProviderTimeParser.offsetInstant("2026-08-01")).isEmpty();
    }

    @Test
    void offsetInstantRejectsGarbageAndMissing() {
        assertThat(ProviderTimeParser.offsetInstant(null)).isEmpty();
        assertThat(ProviderTimeParser.offsetInstant("")).isEmpty();
        assertThat(ProviderTimeParser.offsetInstant("not-a-date")).isEmpty();
    }

    @Test
    void epochSecondParsesNumbersAndNumericStrings() {
        assertThat(ProviderTimeParser.epochSecond(1780000000L))
                .contains(Instant.ofEpochSecond(1780000000L));
        assertThat(ProviderTimeParser.epochSecond("1780000000"))
                .contains(Instant.ofEpochSecond(1780000000L));
        assertThat(ProviderTimeParser.epochSecond(0))
                .contains(Instant.EPOCH);
    }

    @Test
    void epochSecondRejectsNonNumericInput() {
        assertThat(ProviderTimeParser.epochSecond(null)).isEmpty();
        assertThat(ProviderTimeParser.epochSecond("abc")).isEmpty();
        assertThat(ProviderTimeParser.epochSecond("2026-08-01")).isEmpty();
        assertThat(ProviderTimeParser.epochSecond(12.5)).isEmpty();
    }
}
