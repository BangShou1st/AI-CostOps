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
}
