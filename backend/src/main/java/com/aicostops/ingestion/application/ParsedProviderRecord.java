package com.aicostops.ingestion.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One provider-native record produced by {@link ProviderAdapter#parse}. */
public record ParsedProviderRecord(int index, String locator, Map<String, Object> fields) {

    public ParsedProviderRecord {
        if (fields == null || fields.isEmpty()) {
            fields = Map.of();
        } else {
            // Blank spreadsheet cells and missing JSON values surface as null and must
            // survive to normalization instead of failing Map.copyOf.
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }
}
