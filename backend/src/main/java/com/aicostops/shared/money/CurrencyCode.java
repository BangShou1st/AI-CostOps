package com.aicostops.shared.money;

import java.util.Locale;
import java.util.regex.Pattern;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record CurrencyCode(String value) {

    private static final Pattern THREE_ASCII_LETTERS = Pattern.compile("[A-Z]{3}");

    public CurrencyCode {
        if (value == null) {
            throw new IllegalArgumentException("Currency code is required");
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!THREE_ASCII_LETTERS.matcher(value).matches()) {
            throw new IllegalArgumentException("Currency code must contain exactly three ASCII letters");
        }
    }

    @JsonCreator
    public static CurrencyCode of(String value) {
        return new CurrencyCode(value);
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }
}
