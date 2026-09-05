package com.aicostops.gateway.provider.openai;

import java.util.List;

public final class OpenAiWireDtos {

    private OpenAiWireDtos() {
    }

    public record WireRequest(String model, List<WireMessage> messages,
            Integer max_completion_tokens, Boolean stream) {
    }

    public record StreamingWireRequest(String model, List<WireMessage> messages,
            Integer max_completion_tokens, Boolean stream, StreamOptions stream_options) {
    }

    public record StreamOptions(Boolean include_usage) {
    }

    public record WireMessage(String role, String content) {
    }

    public record WireResponse(String id, String object, Long created, String model,
            List<WireChoice> choices, WireUsage usage) {
    }

    public record WireChoice(Integer index, WireMessage message, String finish_reason) {
    }

    public record WireUsage(Integer prompt_tokens, Integer completion_tokens, Integer total_tokens) {
    }

    public record WireChunk(String id, String object, Long created, String model,
            List<WireChunkChoice> choices, WireUsage usage) {
    }

    public record WireChunkChoice(Integer index, WireDelta delta, String finish_reason) {
    }

    public record WireDelta(String role, String content) {
    }
}
