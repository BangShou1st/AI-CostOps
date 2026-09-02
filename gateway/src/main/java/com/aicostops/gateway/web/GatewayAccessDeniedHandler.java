package com.aicostops.gateway.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Deny handler for the API-key surface. A request that reaches the protected
 * exchange without a usable Gateway key is missing credentials, so it is
 * rendered as 401 {@code GATEWAY_AUTH_INVALID} rather than 403.
 */
@Component
public class GatewayAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final GatewayErrorHandler errorHandler;

    public GatewayAccessDeniedHandler(GatewayErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return errorHandler.write(exchange, GatewayErrorCode.GATEWAY_AUTH_INVALID,
                "A valid Gateway key is required", null);
    }
}