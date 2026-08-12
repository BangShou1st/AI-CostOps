package com.aicostops.shared.web;

import java.util.Objects;
import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final ProblemCode code;
    private final String title;

    public DomainException(HttpStatus status, ProblemCode code, String title, String detail) {
        super(detail);
        this.status = Objects.requireNonNull(status, "HTTP status is required");
        this.code = Objects.requireNonNull(code, "Problem code is required");
        this.title = Objects.requireNonNull(title, "Problem title is required");
    }

    public HttpStatus status() {
        return status;
    }

    public ProblemCode code() {
        return code;
    }

    public String title() {
        return title;
    }
}
