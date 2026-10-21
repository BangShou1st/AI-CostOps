package com.aicostops.gateway.provider.opencode;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.provider.shared.AbstractOpenAiCompatibleAdapter;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.routing.OpenCodeFreeOnlyAdmission;
import com.sharedlibs.opencodezen.OpenCodeZenCredentialPolicy;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.OpenCodeZenIdGenerator;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
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
     * Server-owned OpenCode Zen User-Agent. Delegated to the shared library's CLI identity policy so
     * the gateway and the backend control plane cannot drift; the shared default is the verified
     * {@code opencode/latest/2.0.29/cli} CLI string.
     */
    public static final String SERVER_USER_AGENT = OpenCodeZenProtocolPolicy.USER_AGENT;

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
            request.headers().map().forEach((name, values) -> headers.put(name, values));
        } catch (IllegalArgumentException invalidRequest) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE, ProviderHealthSignal.NONE,
                    null, null, false, invalidRequest);
        }
        headers.add("X-Client-Request-Id", boundedRouteDecisionId(context.routeDecisionId()));
    }

    /**
     * Applies the required CLI envelope — {@code stream=true} + {@code stream_options.include_usage}
     * + the shell/read reserved tools — to every outbound chat body. Verified live against the real upstream
     * (2026-10-17): {@code stream=false} is rejected with {@code 403 FreeTierError}, while the full
     * envelope answers {@code 200}, so the incoming {@code stream} flag is intentionally ignored.
     */
    @Override
    protected Object decorateWirePayload(Object payload, ProviderCallContext context, boolean stream) {
        var body = objectMapper.convertValue(payload,
                new tools.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        OpenCodeZenProtocolPolicy.applyRequiredChatEnvelope(body, true);
        return body;
    }

    /**
     * The free tier cannot answer a raw non-streaming request ({@code stream=false} is rejected with
     * {@code 403 FreeTierError}), so a non-streaming caller — a client asking for {@code stream:false}
     * or the Advisor inference worker — is served by consuming the required streaming envelope
     * upstream and aggregating the deltas into one {@link ProviderChatCompletion}. Success requires
     * text content followed by the upstream {@code [DONE]} event; usage is taken from the upstream
     * metering event and is never fabricated.
     */
    @Override
    public Mono<ProviderChatCompletion> complete(ProviderCallContext context,
            ChatCompletionCommand command) {
        return stream(context, command)
                .reduce(new ZenStreamAggregation(), (aggregation, event) -> aggregation.accept(event))
                .map(aggregation -> aggregation.toCompletion(context));
    }

    /** Sequential accumulator turning the required streaming envelope into one completion. */
    private static final class ZenStreamAggregation {

        private final StringBuilder content = new StringBuilder();
        private String upstreamId;
        private long created;
        private String providerModel;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        private boolean hasTextContent;
        private boolean done;

        ZenStreamAggregation accept(ProviderChatStreamEvent event) {
            if (done) {
                throw incompleteStream();
            }
            if (event instanceof ProviderChatStreamEvent.Delta delta) {
                if (delta.deltaContent() != null) {
                    content.append(delta.deltaContent());
                    hasTextContent |= !delta.deltaContent().isEmpty();
                }
                upstreamId = delta.upstreamId();
                created = delta.created();
                providerModel = delta.providerModel();
            } else if (event instanceof ProviderChatStreamEvent.Metering metering) {
                upstreamId = metering.upstreamId();
                created = metering.created();
                providerModel = metering.providerModel();
                promptTokens = metering.promptTokens();
                completionTokens = metering.completionTokens();
                totalTokens = metering.totalTokens();
            } else if (event instanceof ProviderChatStreamEvent.Done) {
                done = true;
            }
            return this;
        }

        ProviderChatCompletion toCompletion(ProviderCallContext context) {
            if (!done || !hasTextContent) {
                throw incompleteStream();
            }
            var usage = promptTokens == null && completionTokens == null && totalTokens == null
                    ? null
                    : new ProviderChatCompletion.ProviderUsage(promptTokens, completionTokens, totalTokens);
            return new ProviderChatCompletion(null, upstreamId, created,
                    providerModel == null ? context.providerModelName() : providerModel,
                    List.of(new ProviderChatCompletion.CompletionChoice(0, content.toString(), "stop")),
                    usage);
        }

        private static ProviderExecutionException incompleteStream() {
            return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, true, null);
        }
    }

    /**
     * A quota-exhausted {@code 429} is a normal bounded outcome for the Zen free tier, not a provider
     * fault, so it must not count toward the circuit breaker. Every other status keeps the shared
     * OpenAI-compatible classification (MiMo / OpenAI / custom adapters are unaffected).
     */
    @Override
    protected ProviderHealthSignal healthSignal(int status) {
        return status == 429 ? ProviderHealthSignal.NONE : super.healthSignal(status);
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
