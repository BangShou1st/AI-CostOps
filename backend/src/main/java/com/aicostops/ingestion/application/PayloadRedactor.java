package com.aicostops.ingestion.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recursively redacts secret-like fields before provider payloads reach MySQL.
 *
 * <p>Key matching is normalized (lowercase, punctuation removed) against
 * {@code password}, {@code token}, {@code secret}, {@code apikey}, {@code authorization}.
 * The rejected value is never logged; it becomes a fixed placeholder.
 */
public final class PayloadRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final List<String> SECRET_FRAGMENTS = List.of(
            "password", "token", "secret", "apikey", "authorization");

    private PayloadRedactor() {
    }

    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            var redacted = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                redacted.put(key, isSecretKey(key) ? REDACTED : redact(entry.getValue()));
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            var redacted = new ArrayList<Object>(list.size());
            for (var item : list) {
                redacted.add(redact(item));
            }
            return redacted;
        }
        return value;
    }

    static boolean isSecretKey(String key) {
        var normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (var fragment : SECRET_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
