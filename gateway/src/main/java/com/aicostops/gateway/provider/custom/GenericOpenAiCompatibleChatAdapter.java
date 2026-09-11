package com.aicostops.gateway.provider.custom;

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
 * Generic custom OpenAI-compatible adapter (M18 V3).
 *
 * <p>Consumes only bounded server-governed context: profile endpoint,
 * completion path and validated auth mapping. No arbitrary headers, body
 * transforms or proxy overrides exist.
 */
@Component
public class GenericOpenAiCompatibleChatAdapter extends AbstractOpenAiCompatibleAdapter {

    public static final String ADAPTER_CODE = "CUSTOM_OPENAI_COMPATIBLE";

    public GenericOpenAiCompatibleChatAdapter(
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
        return null;
    }

    @Override
    protected void validateCredentials(ProviderCallContext context) {
        if ("NONE".equals(context.credentialType())) {
            return;
        }
        if (context.providerSecret() == null
                || (!"BEARER_TOKEN".equals(context.credentialType())
                        && !"API_KEY".equals(context.credentialType()))) {
            throw credentialFailure();
        }
    }

    private ProviderExecutionException credentialFailure() {
        return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ProviderHealthSignal.QUALIFYING_FAILURE,
                null, null, false, null);
    }
}
