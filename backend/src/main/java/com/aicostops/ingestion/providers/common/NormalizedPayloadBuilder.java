package com.aicostops.ingestion.providers.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the M2 intermediate normalized payload shape:
 *
 * <pre>{@code
 * {
 *   "sourceSchema": "<schemaVariant>",
 *   "recordKind": "<provider-neutral coarse kind>",
 *   "dimensions":   { ... },
 *   "usage":        { ... },
 *   "money":        { "currency", "reportedAmount", "components": { ... } },
 *   "providerFields": { ... }
 * }
 * }</pre>
 *
 * <p>Absent values are omitted rather than guessed as zero/null. Empty sections are
 * omitted. Zero is a legitimate provider-reported value and is always retained.
 * Maps stay immutable and deterministically ordered for stable serialization.
 */
public final class NormalizedPayloadBuilder {

    private final String sourceSchema;
    private final String recordKind;
    private final Map<String, Object> dimensions = new LinkedHashMap<>();
    private final Map<String, Object> usage = new LinkedHashMap<>();
    private final Map<String, Object> money = new LinkedHashMap<>();
    private final Map<String, Object> moneyComponents = new LinkedHashMap<>();
    private final Map<String, Object> providerFields = new LinkedHashMap<>();

    public NormalizedPayloadBuilder(String sourceSchema, String recordKind) {
        this.sourceSchema = Objects.requireNonNull(sourceSchema, "sourceSchema must not be null");
        this.recordKind = Objects.requireNonNull(recordKind, "recordKind must not be null");
    }

    public NormalizedPayloadBuilder dimension(String key, Object value) {
        put(dimensions, key, value);
        return this;
    }

    public NormalizedPayloadBuilder usage(String key, Object value) {
        put(usage, key, value);
        return this;
    }

    public NormalizedPayloadBuilder money(String key, Object value) {
        put(money, key, value);
        return this;
    }

    public NormalizedPayloadBuilder moneyComponent(String key, Object value) {
        put(moneyComponents, key, value);
        return this;
    }

    public NormalizedPayloadBuilder providerField(String key, Object value) {
        put(providerFields, key, value);
        return this;
    }

    public Map<String, Object> build() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sourceSchema", sourceSchema);
        payload.put("recordKind", recordKind);
        putIfPresent(payload, "dimensions", dimensions);
        putIfPresent(payload, "usage", usage);
        if (!money.isEmpty() || !moneyComponents.isEmpty()) {
            var moneySection = new LinkedHashMap<>(money);
            putIfPresent(moneySection, "components", moneyComponents);
            payload.put("money", unmodifiable(moneySection));
        }
        putIfPresent(payload, "providerFields", providerFields);
        return unmodifiable(payload);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Map<String, Object> section) {
        if (!section.isEmpty()) {
            target.put(key, unmodifiable(section));
        }
    }

    private static void put(Map<String, Object> section, String key, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return;
        }
        section.put(key, value);
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
