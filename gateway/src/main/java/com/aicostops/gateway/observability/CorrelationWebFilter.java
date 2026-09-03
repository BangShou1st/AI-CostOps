package com.aicostops.gateway.observability;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Assigns one operational {@code X-Trace-Id} per request (preserving a client
 * supplied value) and stores it as an exchange attribute so every downstream
 * handler echoes the same trace id. Trace ids are observability correlation,
 * never business idempotency keys.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CorrelationWebFilter implements WebFilter {

    public static final String TRACE_ATTRIBUTE = "com.aicostops.gateway.traceId";
    private static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var existing = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        var traceId = existing == null || existing.isBlank()
                ? "trc_" + UUID.randomUUID() : existing;
        exchange.getAttributes().put(TRACE_ATTRIBUTE, traceId);
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        return chain.filter(exchange);
    }
}