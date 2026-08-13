package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.shared.security.SecurityProblemWriter;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens,
            SecurityVersionService versions, SecurityProblemWriter problems) throws Exception {
        var bearer = new BearerAuthenticationFilter(tokens, versions, problems);
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
                    problems.unauthorized(request, response, ProblemCode.AUTH_ACCESS_EXPIRED,
                            "Authentication is required.");
                }).accessDeniedHandler((request, response, exception) ->
                        problems.forbidden(request, response, "Access to this resource is forbidden.")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/password/forgot", "/api/v1/auth/password/reset").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/invitations/*/accept").permitAll()
                        .requestMatchers("/api/v1/auth/logout", "/api/v1/auth/logout-all", "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/{id}",
                                "/api/v1/roles", "/api/v1/permissions").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/{id}/status").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/role-assignments").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/invitations").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/role-assignments/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/projects/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/teams").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/teams").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/teams/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects/{id}/members").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/{id}/members").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/{id}/members/{memberId}").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class).build();
    }
}
