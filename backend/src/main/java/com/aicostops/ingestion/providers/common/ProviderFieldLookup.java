package com.aicostops.ingestion.providers.common;

import java.util.Map;

/**
 * Normalized header resolution for provider row maps.
 *
 * <p>Inspection compares headers through {@link HeaderNormalizer} (trim, NFKC,
 * whitespace collapse); row lookup must use the same normalization so a compatible
 * schema always resolves its values. Raw lineage is preserved: this helper never
 * rewrites the raw map, it only resolves reads.
 *
 * <p>Duplicate normalized headers are already rejected by inspection, so a lookup
 * that matches more than one raw key is an invariant violation and fails instead of
 * silently picking the first match.
 */
public final class ProviderFieldLookup {

    private ProviderFieldLookup() {
    }

    public static Object get(Map<String, ?> fields, String canonicalHeader) {
        var key = resolveKey(fields, canonicalHeader);
        return key == null ? null : fields.get(key);
    }

    /** Raw key whose normalized form equals the canonical header, or null. */
    public static String resolveKey(Map<String, ?> fields, String canonicalHeader) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        var target = HeaderNormalizer.normalize(canonicalHeader);
        String match = null;
        for (var key : fields.keySet()) {
            if (HeaderNormalizer.normalize(key).equals(target)) {
                if (match != null) {
                    throw new IllegalStateException(
                            "Ambiguous normalized header '" + target + "' in provider row");
                }
                match = key;
            }
        }
        return match;
    }
}
