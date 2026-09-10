package com.aicostops.gatewayadmin.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates a governed raw Gateway key matching the frozen AIC-092 contract
 * ({@code aic_<12 lowercase Crockford-Base32>_<43 Base64URL chars>}) accepted
 * by {@link GatewayKeyCodec#parse} and the Gateway runtime. Only the secret
 * part is digested; the raw key is returned by the creation endpoint exactly
 * once and is never persisted or logged.
 */
public final class GatewayKeyGenerator {

    private static final String CROCKFORD = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private GatewayKeyGenerator() {
    }

    public static String generateRawKey() {
        StringBuilder prefix = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            prefix.append(CROCKFORD.charAt(RANDOM.nextInt(CROCKFORD.length())));
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String secretPart = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return "aic_" + prefix + "_" + secretPart;
    }
}