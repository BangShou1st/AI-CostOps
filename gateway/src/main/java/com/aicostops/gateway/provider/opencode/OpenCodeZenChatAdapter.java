package com.aicostops.gateway.provider.opencode;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.provider.shared.AbstractOpenAiCompatibleAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenCode Zen built-in adapter (M18 V3).
 *
 * <p>OpenAI-compatible payload, but a distinct transport policy: explicit
 * {@code noProxy()} (DIRECT_ONLY — never inherits HTTP(S)_PROXY, ALL_PROXY
 * or JVM proxy properties) plus the server-owned provider User-Agent.
 * Only BEARER credentials are accepted.
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

    public OpenCodeZenChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            DispatchEndpointGuard endpointGuard) {
        super(builder, objectMapper, properties, endpointGuard, true);
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
        if (!"BEARER_TOKEN".equals(context.credentialType())) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, null);
        }
    }
}
