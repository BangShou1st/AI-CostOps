package com.aicostops.ingestion.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recursively redacts secret-like fields and values before provider payloads reach
 * MySQL.
 *
 * <p>Key matching is normalized (lowercase, punctuation removed) against
 * {@code password}, {@code token}, {@code secret}, {@code apikey}, {@code authorization}.
 * Additionally, every String scalar passes {@link SecretShapes} so secret-shaped
 * values hidden under unknown field names (e.g. {@code sk-...} in a
 * {@code future_note}) fail closed as well. Ordinary provider identities such as
 * {@code keyid_fake} are not secret-shaped and survive. The rejected value is never
 * logged; it becomes a fixed placeholder.
 *
 * <p>JSON object keys are covered too ({@link #sanitizeKey}): a key that itself
 * carries secret material ({@code sk-...}, {@code Bearer ...},
 * {@code api_key=sk-...}) is replaced with a deterministic SHA-256-derived
 * placeholder so the secret never leaves the boundary, distinct dangerous keys
 * never collide, and a second redaction of an already-safe payload is stable.
 * Ordinary schema key names such as {@code model}, {@code usage}, {@code api_key}
 * or {@code token} pass through untouched.
 */
public final class PayloadRedactor {

    public static final String REDACTED = "[REDACTED]";

    /** Prefix of the deterministic placeholder that replaces secret-shaped keys. */
    public static final String REDACTED_KEY_PREFIX = "[REDACTED_KEY:";

    private static final List<String> SECRET_FRAGMENTS = List.of(
            "password", "token", "secret", "apikey", "authorization");

    private PayloadRedactor() {
    }

    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            var redacted = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                var safeKey = sanitizeKey(key);
                var secretShapedKey = !safeKey.equals(key);
                redacted.put(safeKey,
                        isSecretKey(key) || secretShapedKey ? REDACTED : redact(entry.getValue()));
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            var redacted = new ArrayList<Object>(list.size());
            for (var item : list) {
                redacted.add(redact(item));
            }
            return redacted;
        }
        if (value instanceof String text) {
            return SecretShapes.redact(text);
        }
        return value;
    }

    /**
     * Normalized-payload-aware sanitation for the M2 intermediate shape produced
     * by {@code NormalizedPayloadBuilder}: {@code sourceSchema} / {@code recordKind}
     * / {@code dimensions} / {@code usage} / {@code money} / {@code providerFields}.
     *
     * <p>The top-level {@code usage} section is a trusted schema section: numeric
     * meter values under schema keys such as {@code inputTokens} must survive, so
     * key-name redaction ({@code isSecretKey}) is skipped there. Values still pass
     * the recursive {@link SecretShapes} sanitation, secret-shaped object keys are
     * still sanitized everywhere, and every other section keeps the exact
     * fail-closed behavior of {@link #redact(Object)}. The result is the single
     * sanitized source for both {@code raw_provider_record.normalized_payload} and
     * canonicalization input.
     */
    public static Object redactNormalizedPayload(Object value) {
        return redactNormalized(value, true, false);
    }

    private static Object redactNormalized(Object value, boolean topLevel, boolean usageSection) {
        if (value instanceof Map<?, ?> map) {
            var redacted = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var key = String.valueOf(entry.getKey());
                var safeKey = sanitizeKey(key);
                var secretShapedKey = !safeKey.equals(key);
                var isUsageSection = usageSection || (topLevel && "usage".equals(key));
                // Inside the trusted usage section, schema key names (inputTokens
                // etc.) never redact their values; secret-shaped keys and values
                // still fail closed everywhere.
                var redactValue = secretShapedKey || (!isUsageSection && isSecretKey(key));
                redacted.put(safeKey, redactValue ? REDACTED
                        : redactNormalized(entry.getValue(), false, isUsageSection));
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            var redacted = new ArrayList<Object>(list.size());
            for (var item : list) {
                redacted.add(redactNormalized(item, false, usageSection));
            }
            return redacted;
        }
        if (value instanceof String text) {
            return SecretShapes.redact(text);
        }
        return value;
    }

    /**
     * Sanitizes one JSON object key. Keys that contain no secret material are
     * returned unchanged; keys that do ({@code sk-...}, {@code Bearer ...},
     * {@code api_key=sk-...}) become {@code [REDACTED_KEY:<sha256 hex>]}. The
     * digest keeps sibling fields distinct without relying on randomness, and
     * the placeholder itself never matches a secret shape, so already-sanitized
     * persisted payloads pass a second redaction unchanged.
     */
    public static String sanitizeKey(String key) {
        if (key == null) {
            return null;
        }
        return SecretShapes.redact(key).equals(key) ? key : REDACTED_KEY_PREFIX + sha256Hex(key) + "]";
    }

    static boolean isSecretKey(String key) {
        var normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (var fragment : SECRET_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }
}
