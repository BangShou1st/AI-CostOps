package com.aicostops.gateway.auth;

import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Bearer Gateway-key filter. It extracts {@code Authorization: Bearer aic_...}
 * and authenticates through {@link GatewayAuthenticationManager}; the resolved
 * principal is stored in the server WebSession as the security context. Paths
 * without a bearer header fall through to the authorization rule, which rejects
 * them with 401 {@code GATEWAY_AUTH_INVALID}.
 */
@Component
public class GatewayBearerWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ReactiveAuthenticationManager authenticationManager;
    private final GatewayErrorHandler errorHandler;

    public GatewayBearerWebFilter(
            ReactiveAuthenticationManager authenticationManager,
            GatewayErrorHandler errorHandler) {
        this.authenticationManager = authenticationManager;
        this.errorHandler = errorHandler;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return chain.filter(exchange);
        }
        var rawKey = authorization.substring(BEARER_PREFIX.length()).strip();
        var authenticationToken = new UsernamePasswordAuthenticationToken(rawKey, rawKey);
        return authenticationManager.authenticate(authenticationToken)
                .flatMap(authenticated -> exchange.getSession()
                        .flatMap(session -> {
                            session.getAttributes().put(
                                    WebSessionServerSecurityContextRepository
                                            .DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME,
                                    new SecurityContextImpl(authenticated));
                            return chain.filter(exchange);
                        }))
                .switchIfEmpty(Mono.defer(() ->
                        errorHandler.write(exchange, GatewayErrorCode.GATEWAY_AUTH_INVALID,
                                "Invalid Gateway key", null)));
    }
}