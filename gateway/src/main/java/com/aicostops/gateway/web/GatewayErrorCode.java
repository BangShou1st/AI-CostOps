package com.aicostops.gateway.web;

import org.springframework.http.HttpStatus;

/**
 * Frozen AIC-092 Gateway error matrix: every stable code maps to one HTTP
 * status and one OpenAI-compatible {@code error.type}.
 */
public enum GatewayErrorCode {

    GATEWAY_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "invalid_request_error"),
    GATEWAY_AUTH_INVALID(HttpStatus.UNAUTHORIZED, "authentication_error"),
    GATEWAY_FORBIDDEN(HttpStatus.FORBIDDEN, "permission_error"),
    GATEWAY_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "invalid_request_error"),
    GATEWAY_REQUEST_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "invalid_request_error"),
    GATEWAY_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "invalid_request_error"),
    GATEWAY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "conflict_error"),
    GATEWAY_RESPONSE_NOT_RETAINED(HttpStatus.CONFLICT, "conflict_error"),
    GATEWAY_BUDGET_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "insufficient_quota"),
    GATEWAY_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error"),
    GATEWAY_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "server_error"),
    GATEWAY_UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "server_error"),
    GATEWAY_UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "server_error");

    private final HttpStatus status;
    private final String type;

    GatewayErrorCode(HttpStatus status, String type) {
        this.status = status;
        this.type = type;
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }
}