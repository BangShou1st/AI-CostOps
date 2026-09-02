package com.aicostops.gateway.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders the frozen OpenAI-compatible error envelope for every Gateway
 * failure. Messages are client-safe; unexpected exceptions are logged
 * server-side and mapped conservatively without leaking internal detail.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GatewayErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (ex instanceof GatewayErrorException gatewayError) {
            return write(exchange, gatewayError.code(), gatewayError.getMessage(),
                    gatewayError.retryAfterSeconds());
        }
        log.error("Unhandled Gateway error for {}", exchange.getRequest().getPath(), ex);
        return write(exchange, GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                "The gateway could not complete the request", null);
    }

    public Mono<Void> write(ServerWebExchange exchange, GatewayErrorCode code, String message,
            Integer retryAfterSeconds) {
        var response = exchange.getResponse();
        response.setStatusCode(code.status());
        if (retryAfterSeconds != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body = Map.of("error", Map.of(
                "message", message,
                "type", code.type(),
                "param", null,
                "code", code.name()));
        return encode(response, body);
    }

    Mono<Void> encode(ServerHttpResponse response, Map<String, ?> body) {
        var bytes = writeValue(body);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
                .then(Mono.empty());
    }

    private byte[] writeValue(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Gateway error envelope serialization failed", ex);
        }
    }

    /** Safe 401 envelope for missing/invalid credentials after denial. */
    static Map<String, Object> authInvalidBody(GatewayErrorCode code, String message) {
        return Map.of("error", Map.of(
                "message", message,
                "type", code.type(),
                "param", null,
                "code", code.name()));
    }
}