package com.aicostops.gateway.web.dto;

import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict M11 parser for the bounded OpenAI-compatible subset: only
 * {@code model}, {@code messages} (text roles developer/system/user/assistant),
 * optional {@code max_completion_tokens} and {@code stream}. Unknown fields,
 * non-text content and invalid values are rejected.
 */
public final class ChatCompletionRequestParser {

    private static final Set<String> ALLOWED_FIELDS =
            Set.of("model", "messages", "max_completion_tokens", "stream");
    private static final Set<String> ALLOWED_MESSAGE_FIELDS = Set.of("role", "content");
    private static final Set<String> ALLOWED_ROLES =
            Set.of("developer", "system", "user", "assistant");
    private static final Pattern MODEL_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}$");
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_COMPLETION_TOKENS = 131072;

    private ChatCompletionRequestParser() {
    }

    public static ChatCompletionRequest parse(byte[] rawBody, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception ex) {
            throw invalid("Malformed JSON body");
        }
        if (root == null || !root.isObject()) {
            throw invalid("Request body must be a JSON object");
        }
        rejectUnknownFields(root, ALLOWED_FIELDS, "request");

        var modelNode = root.get("model");
        if (modelNode == null || !modelNode.isTextual()) {
            throw invalid("model is required and must be a string");
        }
        var model = modelNode.asText();
        if (model.isEmpty() || model.length() > 100 || !MODEL_PATTERN.matcher(model).matches()) {
            throw invalid("model has an invalid format");
        }

        var messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            throw invalid("messages is required and must be an array");
        }
        if (messagesNode.size() < 1 || messagesNode.size() > MAX_MESSAGES) {
            throw invalid("messages must contain 1..256 messages");
        }
        var messages = new ArrayList<ChatCompletionRequest.ChatMessage>();
        for (JsonNode message : messagesNode) {
            messages.add(parseMessage(message));
        }

        Integer maxCompletionTokens = null;
        var maxTokensNode = root.get("max_completion_tokens");
        if (maxTokensNode != null) {
            if (!maxTokensNode.isIntegralNumber()) {
                throw invalid("max_completion_tokens must be an integer");
            }
            var value = maxTokensNode.asInt();
            if (value < 1 || value > MAX_COMPLETION_TOKENS) {
                throw invalid("max_completion_tokens must be between 1 and " + MAX_COMPLETION_TOKENS);
            }
            maxCompletionTokens = value;
        }

        boolean stream = false;
        var streamNode = root.get("stream");
        if (streamNode != null) {
            if (!streamNode.isBoolean()) {
                throw invalid("stream must be a boolean");
            }
            stream = streamNode.asBoolean();
        }

        return new ChatCompletionRequest(model, messages, maxCompletionTokens, stream);
    }

    private static ChatCompletionRequest.ChatMessage parseMessage(JsonNode message) {
        if (!message.isObject()) {
            throw invalid("Each message must be an object");
        }
        rejectUnknownFields(message, ALLOWED_MESSAGE_FIELDS, "message");
        var roleNode = message.get("role");
        var contentNode = message.get("content");
        if (roleNode == null || !roleNode.isTextual()
                || contentNode == null || !contentNode.isTextual()) {
            throw invalid("Each message requires a string role and a string content");
        }
        var role = roleNode.asText();
        if (!ALLOWED_ROLES.contains(role)) {
            throw invalid("Unsupported message role: " + role);
        }
        return new ChatCompletionRequest.ChatMessage(role, contentNode.asText());
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, String where) {
        for (var entry : ((ObjectNode) object).properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw invalid("Unsupported field in " + where);
            }
        }
    }

    private static GatewayErrorException invalid(String detail) {
        return new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID, detail);
    }
}