package com.aicostops.gateway.web;

/**
 * Business error carrying the frozen AIC-092 error class. The message is
 * client-safe: it never contains prompts, completions, raw keys or arbitrary
 * Provider bodies.
 */
public class GatewayErrorException extends RuntimeException {

    private final GatewayErrorCode code;
    private final Integer retryAfterSeconds;

    public GatewayErrorException(GatewayErrorCode code, String message) {
        this(code, message, null);
    }

    public GatewayErrorException(GatewayErrorCode code, String message, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public GatewayErrorCode code() {
        return code;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}