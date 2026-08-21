package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class CloseBlockerRegistry {

    private final List<CloseBlockerProvider> providers;

    public CloseBlockerRegistry(List<CloseBlockerProvider> discovered) {
        var byCode = new EnumMap<CloseBlockerCode, CloseBlockerProvider>(CloseBlockerCode.class);
        for (var provider : discovered) {
            if (byCode.put(provider.code(), provider) != null) {
                throw new IllegalStateException("Duplicate Close blocker provider: " + provider.code());
            }
        }
        var missing = Arrays.stream(CloseBlockerCode.values())
                .filter(code -> !byCode.containsKey(code))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing Close blocker providers: " + missing);
        }
        this.providers = byCode.values().stream()
                .sorted(Comparator.comparingInt(provider -> provider.code().ordinal()))
                .toList();
    }

    public List<CloseBlockerProvider> providers() {
        return providers;
    }
}
