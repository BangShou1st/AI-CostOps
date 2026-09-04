package com.aicostops.gateway.provider.mimo;

import java.util.List;

/** Frozen MiMo OpenAI-compatible wire shapes for the M11 adapter. */
public final class MimoWireDtos {

    private MimoWireDtos() {
    }

    public record WireRequest(
            String model,
            List<WireMessage> messages,
            Integer max_completion_tokens,
            Boolean stream) {
    }

    public record WireMessage(String role, String content) {
    }

    public record WireResponse(
            String id,
            String object,
            Long created,
            String model,
            List<WireChoice> choices,
            WireUsage usage) {
    }

    public record WireChoice(Integer index, WireMessage message, String finish_reason) {
    }

    public record WireUsage(
            Integer prompt_tokens, Integer completion_tokens, Integer total_tokens) {
    }

    public record WireChunk(
            String id,
            String object,
            Long created,
            String model,
            List<WireChunkChoice> choices,
            WireUsage usage) {
    }

    public record WireChunkChoice(Integer index, WireDelta delta, String finish_reason) {
    }

    public record WireDelta(String role, String content) {
    }
}
