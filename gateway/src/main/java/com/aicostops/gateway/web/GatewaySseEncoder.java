package com.aicostops.gateway.web;

import com.aicostops.gateway.provider.ProviderChatChunk;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes one normalized {@link ProviderChatChunk} into the public
 * OpenAI-compatible {@code chat.completion.chunk} payload carried inside an
 * SSE {@code data:} event. Payloads are emitted incrementally; the stream is
 * never buffered for response replay. The terminal {@code data: [DONE]}
 * payload is a constant so exactly one completion signal is written.
 */
@Component
public final class GatewaySseEncoder {

    public static final String DONE_PAYLOAD = "[DONE]";

    private final ObjectMapper objectMapper;

    public GatewaySseEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Serializes one chunk; {@code fallbackId} is used when the Provider has no upstream id. */
    public String encodeChunk(ProviderChatChunk chunk, String logicalModelKey, String fallbackId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", chunk.upstreamId() == null ? fallbackId : chunk.upstreamId());
        payload.put("object", "chat.completion.chunk");
        payload.put("created", chunk.created());
        payload.put("model", logicalModelKey);
        var delta = new LinkedHashMap<String, Object>();
        if (chunk.deltaContent() != null) {
            delta.put("content", chunk.deltaContent());
        }
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", null);
        payload.put("choices", List.of(choice));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("SSE chunk serialization failed", ex);
        }
    }
}