package com.aicostops.iam.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailAddress {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private EmailAddress() {
    }

    public static String normalize(String rawEmail) {
        if (rawEmail == null) {
            throw new IllegalArgumentException("Email is required");
        }
        var normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!SIMPLE_EMAIL.matcher(normalized).matches() || normalized.length() > 320) {
            throw new IllegalArgumentException("Email is invalid");
        }
        return normalized;
    }
}
