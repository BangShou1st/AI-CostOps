package com.aicostops.providerhub.application;

import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Adapts the shared OpenCode Zen request policy to the bounded Control Plane transport. */
@Component
public final class OpenCodeZenTransportHeaders {

    private final OpenCodeZenRequestFactory requestFactory;

    public OpenCodeZenTransportHeaders(OpenCodeZenRequestFactory requestFactory) {
        this.requestFactory = requestFactory;
    }

    /**
     * Builds model-catalog headers; a missing key uses Zen's public credential.
     * The accept argument is retained for callers; the shared CLI policy owns the wire value.
     */
    public Map<String, String> catalog(URI endpoint, String apiKey, String accept) {
        var request = requestFactory.newRequestBuilder(endpoint, apiKey,
                OpenCodeZenIdGenerator.newSessionId(), OpenCodeZenIdGenerator.newRequestId()).build();
        return headers(request);
    }

    public record PreparedRequest(URI targetUri, Map<String, String> headers) {}

    public String relayUrl() {
        return requestFactory.relayUrl();
    }

    /**
     * Builds both the outbound target URI (rewritten to relay when active) and conforming headers.
     */
    public PreparedRequest prepareProbeRequest(
            URI endpoint, boolean freeModel, String apiKey, String accept) {
        var request = requestFactory.newModelRequestBuilder(endpoint, freeModel, apiKey,
                OpenCodeZenIdGenerator.newSessionId(), OpenCodeZenIdGenerator.newRequestId()).build();
        return new PreparedRequest(request.uri(), headers(request));
    }

    /**
     * Builds model-aware probe headers; the shared policy ignores keys for free models.
     * The shared CLI Accept value also takes precedence over the caller's response preference.
     */
    public Map<String, String> model(
            URI endpoint, boolean freeModel, String apiKey, String accept) {
        return prepareProbeRequest(endpoint, freeModel, apiKey, accept).headers();
    }

    private static Map<String, String> headers(HttpRequest request) {
        var headers = new LinkedHashMap<String, String>();
        request.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        // Keep the shared CLI Accept value. The transport validates the response content type.
        return Map.copyOf(headers);
    }
}
