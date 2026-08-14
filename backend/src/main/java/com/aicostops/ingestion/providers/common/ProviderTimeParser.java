package com.aicostops.ingestion.providers.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Conservative provider time parsing: only unambiguous values become {@link Instant}.
 *
 * <p>ISO strings must carry a {@code Z} or an explicit offset; timezone-less dates
 * and months are rejected so adapters never guess UTC, Beijing time or any other zone.
 * Unix epoch seconds accept integral numbers and numeric strings.
 */
public final class ProviderTimeParser {

    private ProviderTimeParser() {
    }

    public static Optional<Instant> offsetInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        var trimmed = raw.trim();
        try {
            if (trimmed.endsWith("Z") || trimmed.endsWith("z")) {
                return Optional.of(Instant.parse(trimmed));
            }
            return Optional.of(OffsetDateTime.parse(trimmed).toInstant());
        } catch (DateTimeParseException unparseable) {
            return Optional.empty();
        }
    }

    public static Optional<Instant> epochSecond(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw instanceof Number number) {
            if (number instanceof Double || number instanceof Float
                    || number instanceof BigDecimal bigDecimal
                    && bigDecimal.stripTrailingZeros().scale() > 0) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochSecond(number.longValue()));
        }
        if (raw instanceof String text) {
            try {
                return Optional.of(Instant.ofEpochSecond(Long.parseLong(text.trim())));
            } catch (NumberFormatException malformed) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
