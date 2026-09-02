package com.aicostops.gatewayadmin.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Frozen AIC-092 Gateway key contract. A raw key has exactly the shape
 * {@code aic_<12 lowercase Crockford-Base32>_<43 Base64URL chars>}; only the
 * secret part is hashed with the dedicated credential-digest key, and the raw
 * key is never persisted or logged.
 */
public final class GatewayKeyCodec {

    public static final int DIGEST_BYTES = 32;
    private static final Pattern SHAPE =
            Pattern.compile("^aic_([0-9a-hjkmnp-tv-z]{12})_([A-Za-z0-9_-]{43})$");

    private GatewayKeyCodec() {
    }

    /** Parsed non-secret prefix and the secret part of a raw Gateway key. */
    public record ParsedKey(String prefix, String secretPart) {
    }

    public static ParsedKey parse(String rawKey) {
        if (rawKey == null) {
            throw new IllegalArgumentException("Gateway key must not be null");
        }
        var matcher = SHAPE.matcher(rawKey.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Gateway key must match aic_<12 Crockford-Base32>_<43 Base64URL chars>");
        }
        return new ParsedKey(matcher.group(1), matcher.group(2));
    }

    /**
     * Keyed digest over the secret part only. The digest key must be Base64
     * of exactly 32 bytes; the versioned field is recorded separately.
     */
    public static byte[] digestSecret(String secretPart, String hmacKeyBase64) {
        var key = decode32ByteKey("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1", hmacKeyBase64);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(secretPart.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", ex);
        }
    }

    /** Strict Base64 32-byte key validation shared by crypto and prod guards. */
    public static byte[] decode32ByteKey(String envName, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(envName + " must be set to Base64 of exactly 32 bytes");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    envName + " must be valid Base64 of exactly 32 bytes", ex);
        }
        if (key.length != DIGEST_BYTES) {
            throw new IllegalStateException(
                    envName + " must decode to exactly 32 bytes, got " + key.length);
        }
        return key;
    }
}