package com.aicostops.ingestion.application;

import java.util.Map;

/** One provider-native record produced by {@link ProviderAdapter#parse}. */
public record ParsedProviderRecord(int index, String locator, Map<String, Object> fields) {

    public ParsedProviderRecord {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
