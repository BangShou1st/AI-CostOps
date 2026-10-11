package com.aicostops.shared.web;

import org.springframework.http.HttpStatus;

public record PageRequest(int page, int size) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 0) {
            throw invalid("Page must be zero or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("Page size must be between 1 and " + MAX_SIZE);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw invalid("The requested page offset is too large");
        }
    }

    public int offset() {
        return Math.multiplyExact(page, size);
    }

    private static DomainException invalid(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid pagination", detail);
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    public static PageRequest defaults() {
        return new PageRequest(DEFAULT_PAGE, DEFAULT_SIZE);
    }
}
