package com.aicostops.gateway.provider;

/**
 * Resolved server-governed route context plus the decrypted Provider secret
 * for exactly one call. The secret exists only inside this boundary and is
 * never included in {@code toString()}, logs or error bodies. Provider wire
 * authentication headers are deliberately absent; each adapter owns them.
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
        byte[] providerSecret,
        String routeDecisionId) {

    @Override
    public String toString() {
        return "ProviderCallContext[adapter=" + adapterCode
                + " providerAccountId=" + providerAccountId
                + " routeDecisionId=" + routeDecisionId + "]";
    }
}
