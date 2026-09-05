package com.aicostops.gateway.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class ProviderChatAdapterRegistry {

    private final Map<String, ProviderChatAdapter> byCode;

    public ProviderChatAdapterRegistry(List<ProviderChatAdapter> adapters) {
        var normalized = adapters.stream().collect(Collectors.toMap(
                adapter -> normalize(adapter.adapterCode()), Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate Provider adapter code: " + left.adapterCode());
                }));
        this.byCode = Map.copyOf(normalized);
    }

    public ProviderChatAdapter require(String adapterCode) {
        var adapter = byCode.get(normalize(adapterCode));
        if (adapter == null) throw new IllegalStateException("Provider adapter is unavailable: " + adapterCode);
        return adapter;
    }

    public boolean contains(String adapterCode) {
        return adapterCode != null && byCode.containsKey(normalize(adapterCode));
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) throw new IllegalStateException("Provider adapter code is required");
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
