package com.aicostops.gateway.provider.openai;

import java.net.URI;

/** Production allowlist for the server-governed OpenAI endpoint. */
public final class OpenAiEndpointPolicy {

    private static final String APPROVED_HOST = "api.openai.com";

    private OpenAiEndpointPolicy() {
    }

    public static void validate(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Provider base URL is malformed");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !APPROVED_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalStateException("Production requires the approved OpenAI HTTPS endpoint");
        }
    }
}
