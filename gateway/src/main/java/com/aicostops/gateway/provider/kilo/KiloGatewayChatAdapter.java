package com.aicostops.gateway.provider.kilo;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.provider.shared.AbstractOpenAiCompatibleAdapter;
import com.aicostops.gateway.routing.KiloGatewayFreeOnlyAdmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Kilo Gateway built-in adapter (M18 V3).
 *
 * <p>Kilo Gateway is an OpenAI-compatible universal inference gateway
 * ({@code https://ai-gateway.kilo.ai/api/v1}), so it needs no body transform and no bespoke
 * completion path: the default OpenAI Chat Completions wire contract, the shared DIRECT_ONLY
 * (no proxy) transport policy and a neutral User-Agent are used. Free models are dispatched without
 * an {@code Authorization} header (the Control Plane pins a keyless {@code NONE} credential for an
 * admitted free model); every other model keeps the stored {@code Bearer} key.
 *
 * <p>The blocking {@code KiloGatewayClient} shipped by the shared library is deliberately NOT used
 * here: the data plane owns its own bounded reactive transport, and the client is neither reactive
 * nor SSRF-aware. Only the shared free-model policy is borrowed, so the admission rule has one
 * definition.
 */
@Component
public class KiloGatewayChatAdapter extends AbstractOpenAiCompatibleAdapter {

    public static final String ADAPTER_CODE = "KILO_GATEWAY";

    private final KiloGatewayFreeOnlyAdmission freeOnlyAdmission;

    public KiloGatewayChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            DispatchEndpointGuard endpointGuard) {
        this(builder, objectMapper, properties, endpointGuard, new KiloGatewayFreeOnlyAdmission());
    }

    @Autowired
    public KiloGatewayChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            DispatchEndpointGuard endpointGuard,
            KiloGatewayFreeOnlyAdmission freeOnlyAdmission) {
        super(builder, objectMapper, properties, endpointGuard, true);
        this.freeOnlyAdmission = freeOnlyAdmission;
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
        if (isFreeAdmitted(context)) {
            return;
        }
        if (context.providerSecret() == null || !"BEARER_TOKEN".equals(context.credentialType())) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE, ProviderHealthSignal.NONE,
                    null, null, false, null);
        }
    }

    /**
     * A quota-exhausted {@code 429} is a normal bounded outcome for the Kilo free tier, not a
     * provider fault, so it must not count toward the circuit breaker. Every other status keeps the
     * shared OpenAI-compatible classification.
     */
    @Override
    protected ProviderHealthSignal healthSignal(int status) {
        return status == 429 ? ProviderHealthSignal.NONE : super.healthSignal(status);
    }

    private boolean isFreeAdmitted(ProviderCallContext context) {
        return freeOnlyAdmission.admits(ADAPTER_CODE, context.providerModelName());
    }
}
