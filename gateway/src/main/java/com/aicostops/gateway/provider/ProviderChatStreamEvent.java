package com.aicostops.gateway.provider;

/** Provider-neutral stream protocol events. Content, metering and protocol
 * completion are intentionally distinct so usage can never be forwarded as text. */
public sealed interface ProviderChatStreamEvent
        permits ProviderChatStreamEvent.Delta, ProviderChatStreamEvent.Metering,
        ProviderChatStreamEvent.Done {

    record Delta(
            String upstreamId,
            long created,
            String providerModel,
            String deltaContent) implements ProviderChatStreamEvent {
    }

    record Metering(
            String upstreamId,
            long created,
            String providerModel,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens) implements ProviderChatStreamEvent {
    }

    record Done() implements ProviderChatStreamEvent {
    }
}
