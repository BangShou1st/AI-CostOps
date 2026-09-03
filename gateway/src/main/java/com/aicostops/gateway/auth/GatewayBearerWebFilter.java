package com.aicostops.gateway.auth;

import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Bearer Gateway-key filter. It extracts {@code Authorization: Bearer aic_...}
 * and authenticates through {@link GatewayAuthenticationManager}; the resolved
 * {@link GatewayPrincipal} is stored as an exchange attribute consumed by the
 * controllers. Actuator probes are exempt; the billable surface without a
 * usable key receives 401 {@code GATEWAY_AUTH_INVALID}.
 */
@Component
public class GatewayBearerWebFilter implements WebFilter {

    /** Exchange attribute holding the authenticated {@link GatewayPrincipal}. */
    public static final String PRINCIPAL_ATTRIBUTE = "com.aicostops.gateway.principal";

    private static final String BEARER_PREFIX = "Bearer ";

    private final ReactiveAuthenticationManager authenticationManager;

    public GatewayBearerWebFilter(ReactiveAuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        var authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID,
                    "A valid Gateway key is required"));
        }
        var rawKey = authorization.substring(BEARER_PREFIX.length()).strip();
        var authenticationToken = new UsernamePasswordAuthenticationToken(rawKey, rawKey);
        return authenticationManager.authenticate(authenticationToken)
                .flatMap(authenticated -> {
                    exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, authenticated.getPrincipal());
                    return chain.filter(exchange);
                });
    }
}