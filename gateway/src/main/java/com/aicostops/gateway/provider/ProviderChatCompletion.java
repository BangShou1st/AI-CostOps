package com.aicostops.gateway.provider;

import java.util.List;

/**
 * Normalized non-streaming Provider observation. {@link #usage()} is null
 * when the Provider does not expose complete usage: it is never fabricated
 * as zero.
 */
public record ProviderChatCompletion(
        String providerRequestId,
        String upstreamId,
        long created,
        String providerModel,
        List<CompletionChoice> choices,
        ProviderUsage usage) {

    public ProviderChatCompletion {
        choices = List.copyOf(choices);
    }

    public record CompletionChoice(int index, String content, String finishReason) {
    }

    public record ProviderUsage(
            Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}