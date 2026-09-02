package com.aicostops.gateway.config;

import com.aicostops.gateway.auth.GatewayBearerWebFilter;
import com.aicostops.gateway.web.GatewayAccessDeniedHandler;
import com.aicostops.gateway.web.GatewayAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;

/**
 * Data-plane security posture: Gateway-key bearer authentication, actuator
 * probes reachable without a key, everything else requires authentication,
 * and denied access renders the OpenAI-compatible Gateway error envelope.
 */
@Configuration
public class GatewaySecurityConfiguration {

    @Bean
    public SecurityWebFilterChain gatewaySecurityWebFilterChain(
            ServerHttpSecurity http,
            GatewayBearerWebFilter bearerWebFilter,
            GatewayAuthenticationEntryPoint authenticationEntryPoint,
            GatewayAccessDeniedHandler accessDeniedHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(new WebSessionServerSecurityContextRepository())
                .addFilterAt(bearerWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus")
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .build();
    }
}