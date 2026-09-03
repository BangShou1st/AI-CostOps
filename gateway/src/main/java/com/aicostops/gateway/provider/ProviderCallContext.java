package com.aicostops.gateway.provider;

/**
 * Resolved server-governed route context plus the decrypted Provider secret
 * for exactly one call. The secret exists only inside this boundary and is
 * never included in {@code toString()}, logs or error bodies.
 */
public record ProviderCallContext(
        String adapterCode,
        long providerAccountId,
        long providerModelId,
        String providerModelName,
        long pricingVersionId,
        String currency,
        String baseUrl,
        String credentialType,
        String providerKeyHeader,
        byte[] providerSecret) {

    @Override
    public String toString() {
        return "ProviderCallContext[adapter=" + adapterCode
                + " providerAccountId=" + providerAccountId + "]";
    }
}