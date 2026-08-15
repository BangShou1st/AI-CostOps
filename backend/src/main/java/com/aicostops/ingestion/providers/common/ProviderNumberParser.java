package com.aicostops.ingestion.providers.common;

import java.math.BigDecimal;

/**
 * Fail-explicit provider scalar number parsing.
 *
 * <p>Missing (null/blank) and malformed values are distinguishable outcomes;
 * malformed data never silently becomes zero. Locale-style separators such as
 * {@code 1,234.56} are rejected rather than guessed.
 */
public final class ProviderNumberParser {

    private ProviderNumberParser() {
    }

    /** Outcome of one parse: {@code present} distinguishes missing from malformed. */
    public record ParsedValue<T>(T value, boolean present, boolean valid) {

        public boolean missing() {
            return !present;
        }

        public boolean invalid() {
            return present && !valid;
        }
    }

    public static ParsedValue<BigDecimal> decimal(String raw) {
        if (isMissing(raw)) {
            return new ParsedValue<>(null, false, true);
        }
        try {
            return new ParsedValue<>(new BigDecimal(raw.trim()), true, true);
        } catch (NumberFormatException malformed) {
            return new ParsedValue<>(null, true, false);
        }
    }

    public static ParsedValue<Long> longValue(String raw) {
        if (isMissing(raw)) {
            return new ParsedValue<>(null, false, true);
        }
        try {
            return new ParsedValue<>(Long.parseLong(raw.trim()), true, true);
        } catch (NumberFormatException malformed) {
            return new ParsedValue<>(null, true, false);
        }
    }

    private static boolean isMissing(String raw) {
        return raw == null || raw.isBlank();
    }
}
