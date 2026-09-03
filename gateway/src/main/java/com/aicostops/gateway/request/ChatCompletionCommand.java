package com.aicostops.gateway.request;

import java.util.List;

/** Canonical Provider request produced from the accepted public contract. */
public record ChatCompletionCommand(
        String logicalModelKey,
        List<Message> messages,
        int maxCompletionTokens,
        boolean stream) {

    public ChatCompletionCommand {
        messages = List.copyOf(messages);
    }

    public record Message(String role, String content) {
    }
}