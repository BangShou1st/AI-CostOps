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
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
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
                        .requestMatchers(HttpMethod.GET, "/api/v1/cost-centers").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/cost-centers").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/cost-centers/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/provider-accounts").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/provider-accounts").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/provider-accounts/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/routing-policies",
                                "/api/v1/routing-policies/{id}",
                                "/api/v1/routing-options").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/routing-policies",
                                "/api/v1/routing-policies/{id}/revisions",
                                "/api/v1/routing-policies/{id}/activate").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/routing-policies/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects/{id}/members").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/{id}/members").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/{id}/members/{memberId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/teams/{id}/members").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/teams/{id}/members").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/teams/{id}/members/{memberId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/evidence", "/api/v1/evidence/{evidenceId}")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/evidence/{id}/download").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/evidence/{evidenceId}/imports").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/imports", "/api/v1/imports/{importId}",
                                "/api/v1/imports/{importId}/attempts",
                                "/api/v1/imports/{importId}/attempts/{attemptId}/issues",
                                "/api/v1/imports/{importId}/attempts/{attemptId}/raw-records",
                                "/api/v1/imports/{importId}/attempts/{attemptId}/raw-records/{recordId}")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/imports/{importId}/retry",
                                "/api/v1/imports/{importId}/cancel",
                                "/api/v1/imports/{importId}/confirm").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/provider-imports").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/duplicate-candidates/scan").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/duplicate-candidates",
                                "/api/v1/duplicate-candidates/{candidateId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/duplicate-candidates/{candidateId}/keep",
                                "/api/v1/duplicate-candidates/{candidateId}/exclude").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/costs/charges",
                                "/api/v1/costs/charges/{chargeFactId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/costs/charges/{chargeFactId}/allocation-decisions",
                                "/api/v1/allocation-decisions/{decisionId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual")
                        .authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/allocation-decisions/{decisionId}/lines").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/allocation-decisions/{decisionId}/confirm").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/costs/charges/{chargeFactId}/allocation-proposal").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/expenses").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/expenses",
                                "/api/v1/expenses/{expenseId}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/expenses/{expenseId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/expenses/{expenseId}/evidence",
                                "/api/v1/expenses/{expenseId}/submit",
                                "/api/v1/expenses/{expenseId}/cancel",
                                "/api/v1/expenses/{expenseId}/request-info",
                                "/api/v1/expenses/{expenseId}/approve",
                                "/api/v1/expenses/{expenseId}/reject").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/expenses/{expenseId}/evidence/download",
                                "/api/v1/expense-reviews",
                                "/api/v1/expense-reviews/{expenseId}",
                                "/api/v1/expenses/{expenseId}/allocation-decisions").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/expenses/{expenseId}/allocation-decisions/manual").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/allocation-rules",
                                "/api/v1/allocation-rules/{ruleId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/allocation-targets").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/allocation-rules/{ruleKey}/versions",
                                "/api/v1/allocation-rules/{ruleId}/archive").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/billing-periods").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/budgets",
                                "/api/v1/budgets/{budgetId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/budgets").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/budgets/{budgetId}").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/budgets/{budgetId}/commitments").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/commitments",
                                "/api/v1/commitments/{commitmentId}").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/commitments/{commitmentId}/approve",
                                "/api/v1/commitments/{commitmentId}/reject",
                                "/api/v1/commitments/{commitmentId}/cancel",
                                "/api/v1/commitments/{commitmentId}/release").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/costs/charges/{chargeFactId}/post",
                                "/api/v1/expenses/{expenseId}/post",
                                "/api/v1/ledger/corrections").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/ledger/postings",
                                "/api/v1/ledger/postings/{postingId}",
                                "/api/v1/ledger/entries",
                                "/api/v1/ledger/entries/{entryId}").authenticated()
                        // M6 reconciliation and period-close routes. Business
                        // permissions are enforced by M6 application services.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/reconciliation-runs",
                                "/api/v1/reconciliation-runs/{runId}",
                                "/api/v1/reconciliation-runs/{runId}/evidence",
                                "/api/v1/reconciliation-cases",
                                "/api/v1/reconciliation-cases/{caseId}",
                                "/api/v1/reconciliation-cases/{caseId}/evidence",
                                "/api/v1/billing-periods/{periodId}/close-readiness",
                                "/api/v1/billing-periods/{periodId}/close-runs",
                                "/api/v1/billing-periods/{periodId}/close-runs/{runId}").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/reconciliation-runs",
                                "/api/v1/reconciliation-cases/{caseId}/investigate",
                                "/api/v1/reconciliation-cases/{caseId}/return-open",
                                "/api/v1/reconciliation-cases/{caseId}/resolve",
                                "/api/v1/reconciliation-cases/{caseId}/charge-dispositions",
                                "/api/v1/reconciliation-cases/{caseId}/adjustments",
                                "/api/v1/reconciliation-cases/{caseId}/link-correction",
                                "/api/v1/reconciliation-runs/{runId}/gateway-resolutions",
                                "/api/v1/billing-periods/{periodId}/close",
                                "/api/v1/billing-periods/{periodId}/reopen").authenticated()
                        // M7 read-only workbench aggregation. Section content
                        // is permission-trimmed by the reporting service.
                        .requestMatchers(HttpMethod.GET, "/api/v1/workbench").authenticated()
                        // M7 read-only audit query (AIC-065). AUDIT_READ @ ORG
                        // is enforced by the audit application service; the
                        // orgId parameter can never cross organizations.
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit-events").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class).build();
    }
}
