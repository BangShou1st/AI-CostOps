package com.aicostops.gateway.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Single deterministic error boundary. Runs at the very front of the filter
 * chain and suppresses every downstream failure (Gateway authentication,
 * authorization checks and controller errors) into the frozen OpenAI-compatible
 * error envelope, so the framework's generic 500 handler is never reached.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayErrorResponseFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorResponseFilter.class);

    private final ObjectMapper objectMapper;

    public GatewayErrorResponseFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange)
                .onErrorResume(ex -> handle(exchange, ex));
    }

    private Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        GatewayErrorCode code;
        String message;
        Integer retryAfter = null;
        if (ex instanceof GatewayErrorException gatewayError) {
            code = gatewayError.code();
            message = gatewayError.getMessage();
            retryAfter = gatewayError.retryAfterSeconds();
        } else if (ex instanceof ResponseStatusException) {
            code = GatewayErrorCode.GATEWAY_REQUEST_INVALID;
            message = "Malformed or unsupported request";
        } else {
            log.error("Unhandled Gateway error for {}", exchange.getRequest().getPath(), ex);
            code = GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE;
            message = "The gateway could not complete the request";
        }

        var response = exchange.getResponse();
        response.setStatusCode(code.status());
        if (retryAfter != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter.toString());
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var error = new LinkedHashMap<String, Object>();
        error.put("message", message);
        error.put("type", code.type());
        error.put("param", null);
        error.put("code", code.name());
        var body = Map.of("error", error);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception serializationFailure) {
            log.error("Gateway error envelope serialization failed", serializationFailure);
            response.setStatusCode(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
                .then(Mono.empty());
    }
}