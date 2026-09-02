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
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }
}