package com.aicostops.iam.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateUserStatusRequest(
        @NotBlank String status,
        @NotBlank @Pattern(regexp = "0|[1-9][0-9]*")
        @JsonDeserialize(using = DecimalStringDeserializer.class)
        String expectedVersion) {

    public static final class DecimalStringDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(String.class, "A decimal string is required");
            }
            return parser.getString();
        }
    }
}
