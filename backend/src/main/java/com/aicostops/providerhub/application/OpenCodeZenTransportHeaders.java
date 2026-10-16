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

    /** Builds model-catalog headers; a missing key uses Zen's public credential. */
    public Map<String, String> catalog(URI endpoint, String apiKey, String accept) {
        var request = requestFactory.newRequestBuilder(endpoint, apiKey,
                OpenCodeZenIdGenerator.newSessionId(), OpenCodeZenIdGenerator.newRequestId()).build();
        return headers(request, accept);
    }

    /** Builds model-aware probe headers; the shared policy ignores keys for free models. */
    public Map<String, String> model(
            URI endpoint, boolean freeModel, String apiKey, String accept) {
        var request = requestFactory.newModelRequestBuilder(endpoint, freeModel, apiKey,
                OpenCodeZenIdGenerator.newSessionId(), OpenCodeZenIdGenerator.newRequestId()).build();
        return headers(request, accept);
    }

    private static Map<String, String> headers(HttpRequest request, String accept) {
        var headers = new LinkedHashMap<String, String>();
        request.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        if (accept != null && !accept.isBlank()) {
            headers.put("Accept", accept);
        }
        return Map.copyOf(headers);
    }
}
