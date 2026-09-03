package com.aicostops.gateway.provider;

/**
 * One normalized streaming delta or the terminal {@code [DONE]} marker.
 * Streaming never buffers the full completion; the Gateway forwards events
 * incrementally.
 */
public record ProviderChatChunk(
        String upstreamId,
        long created,
        String providerModel,
        String deltaContent,
        boolean done) {
}