package com.aicostops.ingestion.application;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit, deterministic provider-adapter registry.
 *
 * <p>Provider codes are canonicalized to {@code trim().toUpperCase(Locale.ROOT)}.
 * Duplicate canonical registration fails construction; unknown codes resolve empty.
 * Group 1 production may register zero adapters; synthetic adapters are test-only.
 */
public final class ProviderAdapterRegistry {

    private final Map<String, ProviderAdapter> adapters;

    public ProviderAdapterRegistry(List<ProviderAdapter> registeredAdapters) {
        var byCode = new HashMap<String, ProviderAdapter>();
        for (var adapter : registeredAdapters) {
            Objects.requireNonNull(adapter, "ProviderAdapter must not be null");
            var canonical = canonicalCode(adapter.providerCode());
            if (byCode.putIfAbsent(canonical, adapter) != null) {
                throw new IllegalStateException(
                        "Duplicate ProviderAdapter registered for provider code " + canonical);
            }
        }
        this.adapters = Map.copyOf(byCode);
    }

    public Optional<ProviderAdapter> findByCode(String providerCode) {
        return Optional.ofNullable(adapters.get(canonicalCode(providerCode)));
    }

    public static String canonicalCode(String providerCode) {
        return providerCode.trim().toUpperCase(Locale.ROOT);
    }
}
