package com.aicostops.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Data-plane security posture. Authentication is enforced by
 * {@code GatewayBearerWebFilter} + {@code GatewayAuthenticationManager}
 * (attribute-based, no web sessions); this chain only disables the built-in
 * mechanisms and permits the surface so the bearer filter and the controllers
 * own the 401/403 semantics.
 */
@Configuration
public class GatewaySecurityConfiguration {

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Deliberate bearer-plane posture (frozen AIC-091: "API-key
                // surface, not a browser cookie product"): the data plane
                // authenticates via Authorization Bearer header only, issues no
                // cookies and keeps no server session, so the cookie/session
                // CSRF attack model does not apply.
                // codeql[java/spring-disabled-csrf-protection]
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }
}