package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssueSanitizerTest {

    @Test
    void locatorNullStaysNull() {
        assertThat(IssueSanitizer.sanitizeLocator(null)).isNull();
    }

    @Test
    void locatorRedactsSecretShapedFragments() {
        assertThat(IssueSanitizer.sanitizeLocator("sk-SECRET-SENTINEL-DO-NOT-PERSIST"))
                .isEqualTo("[REDACTED]");
        assertThat(IssueSanitizer.sanitizeLocator("sk-SECRET-SENTINEL-DO-NOT-PERSIST-notes.txt"))
                .isEqualTo("[REDACTED].txt");
        assertThat(IssueSanitizer.sanitizeLocator("api_key=live-value.csv"))
                .isEqualTo("api_key=[REDACTED]");
        assertThat(IssueSanitizer.sanitizeLocator("Bearer eyJraWQiLCJhbGciOiJSUzI1NiJ9.csv"))
                .isEqualTo("Bearer [REDACTED]");
    }

    @Test
    void locatorTruncatesAtFiveHundredChars() {
        var longLocator = "a".repeat(600);

        var sanitized = IssueSanitizer.sanitizeLocator(longLocator);

        assertThat(sanitized).hasSize(IssueSanitizer.MAX_LOCATOR_LENGTH);
    }

    @Test
    void locatorUnderLimitStaysUntouched() {
        var locator = "amount.csv:row:42";

        assertThat(IssueSanitizer.sanitizeLocator(locator)).isEqualTo(locator);
    }

    @Test
    void fieldNameNullStaysNull() {
        assertThat(IssueSanitizer.sanitizeFieldName(null)).isNull();
    }

    @Test
    void fieldNameRedactsSecretShapedFragments() {
        assertThat(IssueSanitizer.sanitizeFieldName("sk-SECRET-SENTINEL-DO-NOT-PERSIST"))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void fieldNameTruncatesAtTwoHundredChars() {
        var longField = "c".repeat(250);

        var sanitized = IssueSanitizer.sanitizeFieldName(longField);

        assertThat(sanitized).hasSize(IssueSanitizer.MAX_FIELD_NAME_LENGTH);
    }

    @Test
    void fieldNameUnderLimitStaysUntouched() {
        assertThat(IssueSanitizer.sanitizeFieldName("用户ID")).isEqualTo("用户ID");
    }
}
