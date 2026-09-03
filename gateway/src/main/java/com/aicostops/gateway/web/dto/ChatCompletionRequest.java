package com.aicostops.gateway.web.dto;

import java.util.List;

/** Public M11 Chat Completions request subset (text roles only). */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Integer maxCompletionTokens,
        boolean stream) {

    public ChatCompletionRequest {
        messages = List.copyOf(messages);
    }

    public record ChatMessage(String role, String content) {
    }
}