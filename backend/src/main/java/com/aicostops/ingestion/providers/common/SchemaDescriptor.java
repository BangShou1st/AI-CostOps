package com.aicostops.ingestion.providers.common;

import com.aicostops.ingestion.domain.ImportSourceType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Canonical schema descriptor used only for fingerprinting.
 *
 * <p>Contains provider code, source type, schema variant and logical roles with
 * their normalized header sets. It deliberately has no field for business row
 * values: amounts, tokens, user IDs, API keys and dates never participate.
 */
public record SchemaDescriptor(
        String providerCode,
        ImportSourceType sourceType,
        String schemaVariant,
        Map<String, List<String>> roles) {

    public SchemaDescriptor {
        Objects.requireNonNull(providerCode, "providerCode must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(schemaVariant, "schemaVariant must not be null");
        roles = roles == null ? Map.of() : roles.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
}
