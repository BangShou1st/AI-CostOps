package com.aicostops.gateway.web;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 401 entry point writing the OpenAI-compatible {@code GATEWAY_AUTH_INVALID} envelope. */
@Component
public class GatewayAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final GatewayErrorHandler errorHandler;

    public GatewayAuthenticationEntryPoint(GatewayErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return errorHandler.write(exchange, GatewayErrorCode.GATEWAY_AUTH_INVALID,
                "Missing or invalid Gateway key", null);
    }
}