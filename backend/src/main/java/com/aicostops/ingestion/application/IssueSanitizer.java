package com.aicostops.ingestion.application;

import java.util.regex.Pattern;

/**
 * Fail-closed sanitization for ImportIssue text before it reaches MySQL.
 *
 * <p>{@code message} keeps diagnostic value but secret-shaped fragments
 * ({@code password=...}, {@code token=...}, {@code api_key=...},
 * {@code Authorization: ...}, {@code Bearer ...}, {@code sk-...}) are replaced.
 * {@code rawValueMasked} only accepts strict masked representations; anything else
 * becomes {@code [REDACTED]} rather than trusting adapter input.
 *
 * <p>{@code recordLocator} and {@code fieldName} may contain user-controlled data
 * (original filenames, ZIP entry names, raw CSV/XLSX headers). They are sanitized
 * with the same secret-shaped redaction and truncated to their DB column limits so
 * no adapter can bypass the boundary or cause a DB truncation error.
 */
public final class IssueSanitizer {

    public static final String REDACTED = "[REDACTED]";
    public static final int MAX_MESSAGE_LENGTH = 400;
    public static final int MAX_LOCATOR_LENGTH = 500;
    public static final int MAX_FIELD_NAME_LENGTH = 200;

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|token|secret|api[_-]?key|authorization)\\s*[=:]\\s*\\S+");
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_SHAPED_VALUE = Pattern.compile(
            "(?i)sk-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}");
    private static final Pattern STRICT_MASK = Pattern.compile("(\\[REDACTED\\]|\\*{1,64}|x{1,64})");

    private IssueSanitizer() {
    }

    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        var safe = BEARER_CREDENTIAL.matcher(message).replaceAll("Bearer " + REDACTED);
        safe = SECRET_ASSIGNMENT.matcher(safe).replaceAll("$1=" + REDACTED);
        safe = SECRET_SHAPED_VALUE.matcher(safe).replaceAll(REDACTED);
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

    public static String sanitizeLocator(String recordLocator) {
        if (recordLocator == null) {
            return null;
        }
        return truncate(redactSecretShaped(recordLocator), MAX_LOCATOR_LENGTH);
    }

    public static String sanitizeFieldName(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        return truncate(redactSecretShaped(fieldName), MAX_FIELD_NAME_LENGTH);
    }

    private static String redactSecretShaped(String text) {
        var safe = SECRET_ASSIGNMENT.matcher(text).replaceAll("$1=" + REDACTED);
        safe = BEARER_CREDENTIAL.matcher(safe).replaceAll("Bearer " + REDACTED);
        return SECRET_SHAPED_VALUE.matcher(safe).replaceAll(REDACTED);
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
