package com.aicostops.reconciliation.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded Provider/source-schema correlation profile registry.
 *
 * <p>A provider code listed in {@code
 * aicostops.reconciliation.correlation-certified-providers} explicitly
 * certifies that its persisted {@code raw_provider_record.provider_record_key}
 * represents the Provider request id. Every provider that is not certified
 * resolves to {@code NONE}: the matcher then stays aggregate and never infers
 * request-id semantics from a generic key. The matcher consumes persisted
 * canonical lineage only; it never re-reads raw Provider payloads.
 */
@Component
public class ProviderCorrelationProfileRegistry {

    public enum CorrelationField {
        PROVIDER_REQUEST_ID,
        NONE
    }

    private final Set<String> certifiedProviderCodes;

    public ProviderCorrelationProfileRegistry(
            @Value("${aicostops.reconciliation.correlation-certified-providers:}")
            List<String> certifiedProviderCodes) {
        this.certifiedProviderCodes = certifiedProviderCodes == null ? Set.of()
                : certifiedProviderCodes.stream()
                        .filter(code -> code != null && !code.isBlank())
                        .map(code -> code.strip().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    /** Certified semantics for one provider's persisted record key. */
    public CorrelationField providerRecordKeySemantics(String providerCode) {
        if (providerCode == null) {
            return CorrelationField.NONE;
        }
        return certifiedProviderCodes.contains(providerCode.strip().toUpperCase(Locale.ROOT))
                ? CorrelationField.PROVIDER_REQUEST_ID
                : CorrelationField.NONE;
    }
}
