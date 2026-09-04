package com.aicostops.gateway.metering;

import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import java.time.Instant;
import java.util.Map;

/** Bounded Provider evidence presented to the frozen-pricing classifier. */
public record GatewayUsageObservation(
        Integer inputTokens,
        Integer outputTokens,
        Integer cachedInputTokens,
        Integer totalTokens,
        boolean providerUsageFinal,
        boolean dispatched,
        Instant providerBillingTimestamp,
        Instant providerRequestTimestamp,
        Instant dispatchIntentAt,
        String providerRequestId,
        String providerCompletionId,
        Map<String, String> safeProviderMetadata) {

    public GatewayUsageObservation {
        safeProviderMetadata = safeProviderMetadata == null
                ? Map.of() : Map.copyOf(safeProviderMetadata);
    }

    public static GatewayUsageObservation providerFinal(
            Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
            Instant providerRequestTimestamp) {
        return new GatewayUsageObservation(inputTokens, outputTokens, cachedInputTokens,
                null, true, true, null, providerRequestTimestamp, providerRequestTimestamp,
                null, null, Map.of());
    }

    public static GatewayUsageObservation providerFinal(
            Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
            Integer totalTokens, Instant providerRequestTimestamp) {
        return new GatewayUsageObservation(inputTokens, outputTokens, cachedInputTokens,
                totalTokens, true, true, null, providerRequestTimestamp,
                providerRequestTimestamp, null, null, Map.of());
    }

    public static GatewayUsageObservation providerPartial(
            Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
            Instant dispatchIntentAt) {
        return new GatewayUsageObservation(inputTokens, outputTokens, cachedInputTokens,
                null, false, true, null, null, dispatchIntentAt, null, null, Map.of());
    }

    public static GatewayUsageObservation noUsage(Instant dispatchIntentAt) {
        return new GatewayUsageObservation(null, null, null, null, false, false,
                null, null, dispatchIntentAt, null, null, Map.of());
    }

    public GatewayUsageObservation withDispatched(boolean value) {
        return new GatewayUsageObservation(inputTokens, outputTokens, cachedInputTokens,
                totalTokens, providerUsageFinal, value, providerBillingTimestamp, providerRequestTimestamp,
                dispatchIntentAt, providerRequestId, providerCompletionId, safeProviderMetadata);
    }

    public static GatewayUsageObservation fromCompletion(
            ProviderChatCompletion completion, Instant dispatchIntentAt) {
        var usage = completion == null ? null : completion.usage();
        var providerTimestamp = completion != null && completion.created() > 0
                ? Instant.ofEpochSecond(completion.created()) : null;
        Map<String, String> metadata = completion != null && completion.upstreamId() != null
                ? Map.of("provider_completion_id", completion.upstreamId()) : Map.of();
        return new GatewayUsageObservation(
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                null,
                usage == null ? null : usage.totalTokens(),
                usage != null,
                true,
                null,
                providerTimestamp,
                dispatchIntentAt,
                completion == null ? null : completion.providerRequestId(),
                completion == null ? null : completion.upstreamId(),
                metadata);
    }

    public static GatewayUsageObservation fromMetering(
            ProviderChatStreamEvent.Metering metering, Instant dispatchIntentAt) {
        var providerTimestamp = metering.created() > 0
                ? Instant.ofEpochSecond(metering.created()) : null;
        Map<String, String> metadata = metering.upstreamId() == null
                ? Map.of() : Map.of("provider_completion_id", metering.upstreamId());
        return new GatewayUsageObservation(
                metering.promptTokens(), metering.completionTokens(), null,
                metering.totalTokens(),
                true, true, null, providerTimestamp, dispatchIntentAt,
                null, metering.upstreamId(), metadata);
    }
}
