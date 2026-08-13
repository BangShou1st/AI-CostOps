package com.aicostops.shared.web;

import java.util.Objects;
import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final ProblemCode code;
    private final String title;
    private final long retryAfterSeconds;

    public DomainException(HttpStatus status, ProblemCode code, String title, String detail) {
        this(status, code, title, detail, 0);
    }

    public DomainException(HttpStatus status, ProblemCode code, String title, String detail, long retryAfterSeconds) {
        super(detail);
        this.status = Objects.requireNonNull(status, "HTTP status is required");
        this.code = Objects.requireNonNull(code, "Problem code is required");
        this.title = Objects.requireNonNull(title, "Problem title is required");
        this.retryAfterSeconds = retryAfterSeconds;
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

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
