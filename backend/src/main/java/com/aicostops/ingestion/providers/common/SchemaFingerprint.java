package com.aicostops.ingestion.providers.common;

import com.aicostops.ingestion.domain.ImportSourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Deterministic SHA-256 fingerprint over a canonical {@link SchemaDescriptor}.
 *
 * <p>Logical-role keys and normalized headers are sorted before hashing, so column
 * order, sheet order and archive entry order never change the fingerprint. Business
 * values are not part of the descriptor and therefore never enter the hash.
 */
public final class SchemaFingerprint {

    private SchemaFingerprint() {
    }

    public static String sha256(SchemaDescriptor descriptor) {
        var canonical = new StringBuilder()
                .append(descriptor.providerCode()).append('\n')
                .append(descriptor.sourceType()).append('\n')
                .append(descriptor.schemaVariant()).append('\n');
        descriptor.roles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey()).append('=')
                        .append(entry.getValue().stream().sorted(Comparator.naturalOrder()).toList())
                        .append('\n'));
        return digest(canonical.toString());
    }

    private static String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    /** Convenience overload for adapters that build the descriptor inline. */
    public static String sha256(
            String providerCode,
            ImportSourceType sourceType,
            String schemaVariant,
            Map<String, List<String>> roles) {
        return sha256(new SchemaDescriptor(providerCode, sourceType, schemaVariant, roles));
    }
}
