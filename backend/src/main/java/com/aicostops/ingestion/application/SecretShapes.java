package com.aicostops.ingestion.application;

import java.util.regex.Pattern;

/**
 * Fail-closed secret-shaped text sanitation shared by {@link IssueSanitizer} and
 * {@link PayloadRedactor}.
 *
 * <p>Safe text is preserved; secret-shaped substrings are replaced with
 * {@code [REDACTED]}. Patterns cover credential assignments
 * ({@code password=...}, {@code token=...}, {@code secret=...},
 * {@code api_key=...}, {@code authorization=...}), bearer credentials, and common
 * credential value shapes ({@code sk-...}, {@code ghp_...}, {@code AKIA...}).
 * Ordinary provider identities such as {@code keyid_fake} or {@code proj_fake} are
 * not matched and survive untouched.
 */
public final class SecretShapes {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|token|secret|api[_-]?key|authorization)\\s*[=:]\\s*\\S+");
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_SHAPED_VALUE = Pattern.compile(
            "(?i)sk-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}");

    private SecretShapes() {
    }

    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // Bearer credentials first: the assignment pattern would otherwise swallow
        // the "Bearer" prefix and leave the bare credential behind.
        var safe = BEARER_CREDENTIAL.matcher(text).replaceAll("Bearer " + REDACTED);
        safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1=" + REDACTED);
        return SECRET_SHAPED_VALUE.matcher(safe).replaceAll(REDACTED);
    }
}
