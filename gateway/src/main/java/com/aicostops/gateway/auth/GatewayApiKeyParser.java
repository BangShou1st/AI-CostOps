package com.aicostops.gateway.auth;

import java.util.regex.Pattern;

/**
 * Frozen AIC-092 Gateway key shape: {@code aic_<12 Crockford-Base32>_<43
 * Base64URL chars>}. Only the prefix is a lookup key; the secret part is
 * compared through its keyed digest.
 */
public final class GatewayApiKeyParser {

    private static final Pattern SHAPE =
            Pattern.compile("^aic_([0-9a-hjkmnp-tv-z]{12})_([A-Za-z0-9_-]{43})$");

    private GatewayApiKeyParser() {
    }

    public record ParsedKey(String prefix, String secretPart) {
    }

    public static ParsedKey parse(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("Missing Gateway key");
        }
        var matcher = SHAPE.matcher(bearerToken.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Gateway key shape");
        }
        return new ParsedKey(matcher.group(1), matcher.group(2));
    }
}