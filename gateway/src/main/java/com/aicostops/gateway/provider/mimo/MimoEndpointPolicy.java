package com.aicostops.gateway.provider.mimo;

import java.net.URI;

/**
 * Production Provider endpoint boundary (AIC-091/092): external Provider
 * destinations must be HTTPS and land on the Adapter-approved host. Client
 * requests never supply base URLs; this policy only ever sees server-governed
 * catalog values.
 */
public final class MimoEndpointPolicy {

    private static final String APPROVED_HOST = "api.xiaomimimo.com";

    private MimoEndpointPolicy() {
    }

    public static void validate(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Provider base URL is malformed");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Production requires an HTTPS Provider endpoint");
        }
        if (!APPROVED_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalStateException("Provider host is not approved for production");
        }
    }
}