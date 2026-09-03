package com.aicostops.gateway.web.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public OpenAI-style Chat Completion response. {@code usage} is optional:
 * when the Provider exposes no complete usage it stays absent and is never
 * fabricated as zero.
 */
public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage) {

    public ChatCompletionResponse {
        choices = List.copyOf(choices);
    }

    public record Choice(int index, Message message, String finishReason) {
    }

    public record Message(String role, String content) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }

    /** Serializable map with {@code usage} omitted when absent. */
    public Map<String, Object> toJsonValue() {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("object", object);
        result.put("created", created);
        result.put("model", model);
        result.put("choices", choices.stream()
                .map(choice -> {
                    var choiceMap = new LinkedHashMap<String, Object>();
                    choiceMap.put("index", choice.index());
                    choiceMap.put("message", Map.of(
                            "role", choice.message().role(),
                            "content", choice.message().content()));
                    choiceMap.put("finish_reason", choice.finishReason());
                    return choiceMap;
                })
                .toList());
        if (usage != null) {
            result.put("usage", Map.of(
                    "prompt_tokens", usage.promptTokens(),
                    "completion_tokens", usage.completionTokens(),
                    "total_tokens", usage.totalTokens()));
        }
        return result;
    }
}