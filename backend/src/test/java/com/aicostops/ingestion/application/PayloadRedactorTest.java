package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadRedactorTest {

    @Test
    void secretShapedStringValuesAreRedactedFailClosed() {
        var redacted = PayloadRedactor.redact(
                Map.of("future_note", "sk-SECRET-SENTINEL-DO-NOT-PERSIST"));

        assertThat(redacted).isEqualTo(Map.of("future_note", "[REDACTED]"));
    }

    @Test
    void secretShapedSubstringsAreRedactedInsideLongerValues() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "note", "prefix sk-abc12345xyz suffix",
                "header", "Authorization: Bearer eyJraWQiLCJhbGciOiJSUzI1NiJ9 rest"));

        assertThat(redacted.get("note")).isEqualTo("prefix [REDACTED] suffix");
        assertThat(String.valueOf(redacted.get("header"))).doesNotContain("eyJraWQi");
    }

    @Test
    void safeProviderIdentitiesArePreserved() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "credentialId", "keyid_fake",
                "project", "proj_fake",
                "model", "gpt-example"));

        assertThat(redacted).containsEntry("credentialId", "keyid_fake")
                .containsEntry("project", "proj_fake")
                .containsEntry("model", "gpt-example");
    }

    @Test
    void keyBasedRedactionStillAppliesToNestedStructures() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "api_key", "live-value",
                "nested", Map.of("safe", "keep-me", "auth_token", "tok-1"),
                "list", List.of(Map.of("secret", "s1"))));

        assertThat(redacted.get("api_key")).isEqualTo("[REDACTED]");
        assertThat(((Map<?, ?>) redacted.get("nested")).get("auth_token")).isEqualTo("[REDACTED]");
        assertThat(((Map<?, ?>) redacted.get("nested")).get("safe")).isEqualTo("keep-me");
        assertThat(((List<?>) redacted.get("list")).get(0)).isEqualTo(Map.of("secret", "[REDACTED]"));
    }

    @Test
    void ordinarySafeTextSurvives() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "user_id", "user-1",
                "period", "2026-08-01 00:00:00 - 2026-08-31 23:59:59"));

        assertThat(redacted).containsEntry("user_id", "user-1")
                .containsEntry("period", "2026-08-01 00:00:00 - 2026-08-31 23:59:59");
    }

    // ------------------------------------------------------------------
    // R1 review fix: secret-shaped JSON object keys must never leak
    // ------------------------------------------------------------------

    @Test
    void secretShapedObjectKeysAreSanitizedWhileSafeKeysSurvive() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "sk-SECRET-SENTINEL-DO-NOT-RETURN", "anything",
                "model", "gpt-example"));

        assertThat(redacted).containsEntry("model", "gpt-example");
        assertThat(redacted).hasSize(2);
        assertThat(redacted).doesNotContainKey("sk-SECRET-SENTINEL-DO-NOT-RETURN");
        assertThat(redacted.toString()).doesNotContain("SECRET-SENTINEL");
        // The dangerous key is replaced by exactly one deterministic placeholder.
        assertThat(redacted.keySet()).filteredOn(
                key -> key.startsWith("[REDACTED_KEY:")).hasSize(1);
    }

    @Test
    void secretShapedKeyEntriesFailClosedWithRedactedValue() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(
                Map.of("sk-SECRET-SENTINEL-DO-NOT-RETURN", "live-value"));

        assertThat(redacted).containsValue("[REDACTED]");
    }

    @Test
    void nestedSecretShapedObjectKeysAreSanitizedRecursively() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "safe", Map.of("sk-NESTED-SECRET-SENTINEL-123", "v", "usage", 5),
                "list", List.of(Map.of("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9", "t"))));

        assertThat(redacted.toString())
                .doesNotContain("sk-NESTED-SECRET-SENTINEL-123", "eyJhbGciOiJSUzI1NiJ9");
        assertThat(redacted.toString()).contains("safe", "list", "usage");
    }

    @Test
    void ordinarySchemaKeyNamesSurviveIncludingApiKeyAndToken() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "model", "gpt-example",
                "usage", 12.5,
                "credentialId", "keyid_fake",
                "api_key", "live-value",
                "token", "tok-1",
                "future_note", "note"));

        assertThat(redacted).containsKeys("model", "usage", "credentialId", "api_key", "token", "future_note");
        assertThat(redacted.keySet()).noneMatch(key -> key.startsWith("[REDACTED_KEY:"));
        // api_key/token keep their field identity; only their values are redacted.
        assertThat(redacted).containsEntry("api_key", "[REDACTED]").containsEntry("token", "[REDACTED]");
        assertThat(redacted).containsEntry("model", "gpt-example").containsEntry("usage", 12.5);
    }

    @Test
    void distinctDangerousKeysDoNotCollideAfterSanitization() {
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) PayloadRedactor.redact(Map.of(
                "sk-SECRET-KEY-AAAAAAAAAAAA", 1,
                "api_key=sk-SECRET-KEY-BBBBBBBBBBBB", 2,
                "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", 3));

        var sanitizedKeys = redacted.keySet().stream()
                .filter(key -> key.startsWith("[REDACTED_KEY:"))
                .toList();
        assertThat(sanitizedKeys).hasSize(3).doesNotHaveDuplicates();
        assertThat(redacted.toString()).doesNotContain("SECRET-KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
    }

    @Test
    void keySanitizationIsDeterministicAndIdempotentAtTheReadBoundary() {
        var first = PayloadRedactor.redact(Map.of("sk-SECRET-KEY-AAAAAAAAAAAA", "x"));
        var second = PayloadRedactor.redact(Map.of("sk-SECRET-KEY-AAAAAAAAAAAA", "x"));
        assertThat(second).isEqualTo(first);

        // Already-sanitized persisted payloads pass a second redaction untouched
        // (persistence + read boundary both run PayloadRedactor).
        assertThat(PayloadRedactor.redact(first)).isEqualTo(first);
    }

    // ------------------------------------------------------------------
    // R1 review fix: normalized-payload-aware sanitation
    // ------------------------------------------------------------------

    @Test
    void normalizedUsageMeterNumbersSurviveTokenLikeSchemaKeys() {
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) PayloadRedactor.redactNormalizedPayload(Map.of(
                "sourceSchema", "openai.organization-usage-completions-json.v1",
                "recordKind", "USAGE",
                "usage", Map.of(
                        "inputTokens", 123L,
                        "outputTokens", 0L,
                        "numModelRequests", 5L),
                "dimensions", Map.of("providerUser", "user_x")));

        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) sanitized.get("usage");
        assertThat(usage).containsEntry("inputTokens", 123L)
                .containsEntry("outputTokens", 0L)
                .containsEntry("numModelRequests", 5L);
        assertThat(usage).doesNotContainValue("[REDACTED]");
    }

    @Test
    void normalizedUsageSecretShapedStringValuesAreStillRedacted() {
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) PayloadRedactor.redactNormalizedPayload(Map.of(
                "usage", Map.of("totalAudioDurationRaw", "sk-SECRET-SENTINEL-12345678"),
                "dimensions", Map.of("model", "gpt-example")));

        assertThat(sanitized.toString()).doesNotContain("SECRET-SENTINEL");
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) sanitized.get("usage");
        assertThat(usage.get("totalAudioDurationRaw")).isEqualTo("[REDACTED]");
    }

    @Test
    void normalizedNonUsageSectionsKeepFailClosedKeyRedaction() {
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) PayloadRedactor.redactNormalizedPayload(Map.of(
                "sourceSchema", "openai.organization-costs-json.v1",
                "recordKind", "COST",
                "usage", Map.of("inputTokens", 7L),
                "providerFields", Map.of("credentialLabel", "sk-SECRET-SENTINEL-12345678"),
                "dimensions", Map.of("credentialId", "keyid_fake")));

        assertThat(sanitized.toString()).doesNotContain("SECRET-SENTINEL");
        @SuppressWarnings("unchecked")
        var providerFields = (Map<String, Object>) sanitized.get("providerFields");
        assertThat(providerFields.get("credentialLabel")).isEqualTo("[REDACTED]");
        @SuppressWarnings("unchecked")
        var dimensions = (Map<String, Object>) sanitized.get("dimensions");
        assertThat(dimensions).containsEntry("credentialId", "keyid_fake");
        @SuppressWarnings("unchecked")
        var usage = (Map<String, Object>) sanitized.get("usage");
        assertThat(usage).containsEntry("inputTokens", 7L);
    }

    @Test
    void normalizedSecretShapedObjectKeysAreSanitizedEverywhere() {
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) PayloadRedactor.redactNormalizedPayload(Map.of(
                "usage", Map.of("sk-SECRET-SENTINEL-KEY", 5L),
                "providerFields", Map.of("Authorization: Bearer eyJhbGciOiJSUzI1NiJ9", "t")));

        assertThat(sanitized.toString())
                .doesNotContain("SECRET-SENTINEL", "eyJhbGciOiJSUzI1NiJ9");
    }
}
