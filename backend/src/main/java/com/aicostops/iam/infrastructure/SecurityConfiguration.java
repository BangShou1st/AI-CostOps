package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.SecurityVersionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens,
            SecurityVersionService versions) throws Exception {
        var bearer = new BearerAuthenticationFilter(tokens, versions);
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/problem+json");
                    response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication is required.\",\"code\":\"AUTH_ACCESS_EXPIRED\"}");
                }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/password/forgot", "/api/v1/auth/password/reset",
                                "/api/v1/invitations/*/accept").permitAll()
                        .requestMatchers("/api/v1/auth/logout", "/api/v1/auth/logout-all", "/api/v1/auth/me").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class).build();
    }
}
