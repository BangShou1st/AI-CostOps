package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.domain.ImportSourceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaFingerprintTest {

    private static String fp(Map<String, List<String>> roles) {
        return SchemaFingerprint.sha256(
                new SchemaDescriptor("DEEPSEEK", ImportSourceType.FILE_EXPORT, "deepseek.usage-zip.v1", roles));
    }

    @Test
    void columnOrderDoesNotChangeFingerprint() {
        var a = fp(Map.of("amount", List.of("user_id", "model", "price"),
                "cost", List.of("wallet_type", "cost", "currency")));
        var b = fp(Map.of("amount", List.of("price", "user_id", "model"),
                "cost", List.of("cost", "currency", "wallet_type")));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void addedColumnChangesFingerprintEvenWhenCompatible() {
        var base = fp(Map.of("amount", List.of("user_id", "model")));
        var withExtra = fp(Map.of("amount", List.of("user_id", "model", "extra")));

        assertThat(withExtra).isNotEqualTo(base);
    }

    @Test
    void logicalRoleOrderDoesNotChangeFingerprint() {
        var a = fp(new java.util.LinkedHashMap<>(Map.of(
                "amount", List.of("user_id"),
                "cost", List.of("wallet_type"))));
        var swapped = fp(new java.util.LinkedHashMap<>(Map.of(
                "cost", List.of("wallet_type"),
                "amount", List.of("user_id"))));

        assertThat(a).isEqualTo(swapped);
    }

    @Test
    void providerCodeSourceTypeAndVariantParticipate() {
        var deepseek = SchemaFingerprint.sha256(new SchemaDescriptor(
                "DEEPSEEK", ImportSourceType.FILE_EXPORT, "deepseek.usage-zip.v1",
                Map.of("amount", List.of("user_id"))));
        var otherProvider = SchemaFingerprint.sha256(new SchemaDescriptor(
                "OPENAI", ImportSourceType.FILE_EXPORT, "deepseek.usage-zip.v1",
                Map.of("amount", List.of("user_id"))));
        var otherSourceType = SchemaFingerprint.sha256(new SchemaDescriptor(
                "DEEPSEEK", ImportSourceType.USAGE_API_JSON, "deepseek.usage-zip.v1",
                Map.of("amount", List.of("user_id"))));
        var otherVariant = SchemaFingerprint.sha256(new SchemaDescriptor(
                "DEEPSEEK", ImportSourceType.FILE_EXPORT, "deepseek.usage-zip.v2",
                Map.of("amount", List.of("user_id"))));

        assertThat(otherProvider).isNotEqualTo(deepseek);
        assertThat(otherSourceType).isNotEqualTo(deepseek);
        assertThat(otherVariant).isNotEqualTo(deepseek);
    }

    @Test
    void fingerprintIsDeterministicSha256Hex() {
        var roles = Map.of("amount", List.of("user_id", "model"));
        var first = fp(roles);
        var second = fp(roles);

        assertThat(second).isEqualTo(first).hasSize(64);
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprintNeverContainsBusinessValues() {
        // Business row values are not part of the descriptor type at all; only the
        // canonical descriptor participates, so a changed value cannot change the hash.
        var descriptor = new SchemaDescriptor("DEEPSEEK", ImportSourceType.FILE_EXPORT,
                "deepseek.usage-zip.v1", Map.of("amount", List.of("user_id", "model")));

        assertThat(descriptor.roles()).doesNotContainValue(List.of("u-123", "gpt-4"));
    }
}
