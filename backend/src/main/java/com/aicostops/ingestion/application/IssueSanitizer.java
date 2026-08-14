package com.aicostops.ingestion.application;

import java.util.regex.Pattern;

/**
 * Fail-closed sanitization for ImportIssue text before it reaches MySQL.
 *
 * <p>{@code message} keeps diagnostic value but secret-shaped fragments
 * ({@code password=...}, {@code token=...}, {@code api_key=...},
 * {@code Authorization: ...}, {@code Bearer ...}) are replaced. {@code rawValueMasked}
 * only accepts strict masked representations; anything else becomes
 * {@code [REDACTED]} rather than trusting adapter input.
 */
public final class IssueSanitizer {

    public static final String REDACTED = "[REDACTED]";
    public static final int MAX_MESSAGE_LENGTH = 400;

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|token|secret|api[_-]?key|authorization)\\s*[=:]\\s*\\S+");
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern STRICT_MASK = Pattern.compile("(\\[REDACTED\\]|\\*{1,64}|x{1,64})");

    private IssueSanitizer() {
    }

    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        var safe = BEARER_CREDENTIAL.matcher(message).replaceAll("Bearer " + REDACTED);
        safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1=" + REDACTED);
        return safe.length() <= MAX_MESSAGE_LENGTH
                ? safe
                : safe.substring(0, MAX_MESSAGE_LENGTH);
    }

    public static String sanitizeMasked(String rawValueMasked) {
        if (rawValueMasked == null) {
            return null;
        }
        return STRICT_MASK.matcher(rawValueMasked).matches() ? rawValueMasked : REDACTED;
    }
}
