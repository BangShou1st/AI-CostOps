package com.aicostops.gateway.provider.opencode;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.routing.OpenCodeFreeOnlyAdmission;
import com.aicostops.gateway.provider.shared.AbstractOpenAiCompatibleAdapter;
import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenCode Zen built-in adapter (M18 V3).
 *
 * <p>OpenAI-compatible payload, but a distinct transport policy: explicit
 * {@code noProxy()} (DIRECT_ONLY — never inherits HTTP(S)_PROXY, ALL_PROXY
 * or JVM proxy properties) plus the server-owned provider User-Agent. The
 * app-owned free allow-list uses the shared public credential without loading
 * a stored key; other models follow the shared paid-model key requirement.
 */
@Component
public class OpenCodeZenChatAdapter extends AbstractOpenAiCompatibleAdapter {

    public static final String ADAPTER_CODE = "OPENCODE_ZEN";
    /**
     * Server-owned OpenCode Zen User-Agent. Single source of truth lives in
     * backend {@code ProviderTemplateRegistry#OPENCODE_ZEN_USER_AGENT}; this
     * constant must stay identical ({@code opencode/1.18.21} unless fresh
     * OpenCode evidence proves a deliberate replacement).
     */
    public static final String SERVER_USER_AGENT = "opencode/1.18.21";

    private final OpenCodeZenRequestFactory requestFactory;
    private final OpenCodeFreeOnlyAdmission freeOnlyAdmission;

    public OpenCodeZenChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            DispatchEndpointGuard endpointGuard) {
        this(builder, objectMapper, properties, endpointGuard,
                new OpenCodeZenRequestFactory(SERVER_USER_AGENT), OpenCodeFreeOnlyAdmission.production());
    }

    @Autowired
    public OpenCodeZenChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            DispatchEndpointGuard endpointGuard,
            OpenCodeZenRequestFactory requestFactory,
            OpenCodeFreeOnlyAdmission freeOnlyAdmission) {
        super(builder, objectMapper, properties, endpointGuard, true);
        this.requestFactory = requestFactory;
        this.freeOnlyAdmission = freeOnlyAdmission;
    }

    @Override
    public String adapterCode() {
        return ADAPTER_CODE;
    }

    @Override
    protected String userAgent() {
        return SERVER_USER_AGENT;
    }

    @Override
    protected void validateCredentials(ProviderCallContext context) {
        if (isFreeZenModel(context)) {
            return;
        }
        try {
            OpenCodeZenCredentialPolicy.credentialForModel(false, bearerApiKey(context));
        } catch (IllegalArgumentException missingKey) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE, ProviderHealthSignal.NONE,
                    null, null, false, missingKey);
        }
    }

    @Override
    protected void applyHeaders(HttpHeaders headers, ProviderCallContext context) {
        boolean freeModel = isFreeZenModel(context);
        String apiKey = freeModel ? null : bearerApiKey(context);
        try {
            var request = requestFactory.newModelRequestBuilder(
                    URI.create(targetUrl(context)), freeModel, apiKey,
                    OpenCodeZenIdGenerator.newSessionId(), OpenCodeZenIdGenerator.newRequestId()).build();
            request.headers().map().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
        } catch (IllegalArgumentException invalidRequest) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE, ProviderHealthSignal.NONE,
                    null, null, false, invalidRequest);
        }
        headers.add("X-Client-Request-Id", boundedRouteDecisionId(context.routeDecisionId()));
    }

    private boolean isFreeZenModel(ProviderCallContext context) {
        return freeOnlyAdmission.admits(ADAPTER_CODE, context.providerModelName());
    }

    private static String bearerApiKey(ProviderCallContext context) {
        byte[] secret = context.providerSecret();
        if (!"BEARER_TOKEN".equals(context.credentialType()) || secret == null || secret.length == 0) {
            return null;
        }
        return new String(secret, StandardCharsets.UTF_8);
    }

    private static String boundedRouteDecisionId(String routeDecisionId) {
        if (routeDecisionId == null || routeDecisionId.isBlank()) return "unknown-route";
        var trimmed = routeDecisionId.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }
}
