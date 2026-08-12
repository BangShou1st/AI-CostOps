package com.aicostops.shared.json;

import com.fasterxml.jackson.annotation.JsonValue;

public record ApiId(long value) {

    public static ApiId of(long value) {
        return new ApiId(value);
    }

    @JsonValue
    public String asString() {
        return Long.toString(value);
    }
}
