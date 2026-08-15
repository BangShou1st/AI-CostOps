package com.aicostops.ingestion.application;

import java.util.regex.Pattern;

/**
 * Fail-closed sanitization for ImportIssue text before it reaches MySQL.
 *
 * <p>{@code message} keeps diagnostic value but secret-shaped fragments are replaced
 * via {@link SecretShapes}. {@code rawValueMasked} only accepts strict masked
 * representations; anything else becomes {@code [REDACTED]} rather than trusting
 * adapter input.
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

    private static final Pattern STRICT_MASK = Pattern.compile("(\\[REDACTED\\]|\\*{1,64}|x{1,64})");

    private IssueSanitizer() {
    }

    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        var safe = SecretShapes.redact(message);
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
        return truncate(SecretShapes.redact(recordLocator), MAX_LOCATOR_LENGTH);
    }

    /** Sanitizes a provider record key; same secret-shaped redaction + column bound. */
    public static String sanitizeRecordKey(String providerRecordKey) {
        if (providerRecordKey == null) {
            return null;
        }
        return truncate(SecretShapes.redact(providerRecordKey), MAX_LOCATOR_LENGTH);
    }

    public static String sanitizeFieldName(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        return truncate(SecretShapes.redact(fieldName), MAX_FIELD_NAME_LENGTH);
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
