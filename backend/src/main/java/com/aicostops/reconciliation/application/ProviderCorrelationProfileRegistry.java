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
 * <p>A profile listed in {@code
 * aicostops.reconciliation.correlation-certified-profiles} as
 * {@code PROVIDER:SOURCE_TYPE:PARSER_VERSION} explicitly certifies that the
 * persisted {@code raw_provider_record.provider_record_key} of exactly that
 * provider and durable source schema represents the Provider request id.
 * Certification is never provider-wide: the same provider can export multiple
 * statement schemas whose generic record keys mean different things, so every
 * provider/source-schema combination that is not explicitly listed resolves to
 * {@code NONE} and the matcher stays aggregate. The matcher consumes persisted
 * canonical lineage only; it never re-reads raw Provider payloads.
 */
@Component
public class ProviderCorrelationProfileRegistry {

    public enum CorrelationField {
        PROVIDER_REQUEST_ID,
        NONE
    }

    private final Set<String> certifiedProfiles;

    public ProviderCorrelationProfileRegistry(
            @Value("${aicostops.reconciliation.correlation-certified-profiles:}")
            List<String> certifiedProfiles) {
        this.certifiedProfiles = certifiedProfiles == null ? Set.of()
                : certifiedProfiles.stream()
                        .filter(profile -> profile != null && !profile.isBlank())
                        .map(ProviderCorrelationProfileRegistry::normalize)
                        .collect(Collectors.toUnmodifiableSet());
    }

    /** Certified semantics for one provider's persisted record key. */
    public CorrelationField providerRecordKeySemantics(String providerCode, String sourceType,
            String parserVersion) {
        if (providerCode == null || sourceType == null || parserVersion == null) {
            return CorrelationField.NONE;
        }
        return certifiedProfiles.contains(normalize(
                providerCode + ":" + sourceType + ":" + parserVersion))
                ? CorrelationField.PROVIDER_REQUEST_ID
                : CorrelationField.NONE;
    }

    private static String normalize(String raw) {
        return raw.strip().toUpperCase(Locale.ROOT);
    }
}
