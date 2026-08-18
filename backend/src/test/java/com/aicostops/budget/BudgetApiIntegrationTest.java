package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Budget management HTTP contract: create with server-side scope validation,
 * org-scoped read/list with privacy 404s, manageable-field update with an
 * optimistic version CAS, backend-computed available/overBudget, and audit of
 * every total change. actual_amount / committed_amount are never writable
 * through the management API.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=budget-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class BudgetApiIntegrationTest extends BudgetTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createReturnsBudgetWithComputedReadModel() throws Exception {
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingPeriodId":"%d","scopeType":"PROJECT","scopeId":"%d",
                                 "currency":"CNY","totalAmount":"1000.00000000"}
                                """.formatted(periodId, projectId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.billingPeriodId").value(Long.toString(periodId)))
                .andExpect(jsonPath("$.scopeType").value("PROJECT"))
                .andExpect(jsonPath("$.scopeId").value(Long.toString(projectId)))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.totalAmount").value("1000.00000000"))
                .andExpect(jsonPath("$.actualAmount").value("0.00000000"))
                .andExpect(jsonPath("$.committedAmount").value("0.00000000"))
                .andExpect(jsonPath("$.availableAmount").value("1000.00000000"))
                .andExpect(jsonPath("$.overBudget").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(auditCount("BUDGET_CREATED")).isEqualTo(1);
    }

    @Test
    void createRejectsDuplicateIdentity() throws Exception {
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "1000.00000000")))
                .andExpect(status().isCreated());

        // Same org/period/scope/currency is one budget identity.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "2000.00000000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // Different currency is a different identity and succeeds.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "USD", "1000.00000000")))
                .andExpect(status().isCreated());
    }

    @Test
    void createValidatesScopeAndMoney() throws Exception {
        // A scope id that does not exist in the organization.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", 999_999_999L, "CNY", "1000.00000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // ORG scope must be the budget's own organization.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ORG", foreignOrgId, "CNY", "1000.00000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // A billing period that does not belong to the organization.
        var foreignPeriod = insertBillingPeriod(foreignOrgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"billingPeriodId":"%d","scopeType":"PROJECT","scopeId":"%d",
                                 "currency":"CNY","totalAmount":"1000.00000000"}
                                """.formatted(foreignPeriod, projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Money must be an exact 8-decimal string and total must not be negative.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "1000.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "-1.00000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void readListAndPrivacy() throws Exception {
        var projectBudget = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "100.00000000", "200.00000000");
        var teamBudget = insertBudgetRow(orgId, periodId, "TEAM", teamId, "CNY",
                "500.00000000", "0.00000000", "0.00000000");

        // Org-wide reader sees the detail.
        mockMvc.perform(get("/api/v1/budgets/{budgetId}", projectBudget)
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value("700.00000000"))
                .andExpect(jsonPath("$.overBudget").value(false));

        // List is org-scoped and paged.
        mockMvc.perform(get("/api/v1/budgets")
                        .header("Authorization", readerBearer())
                        .param("billingPeriodId", Long.toString(periodId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].scopeType",
                        org.hamcrest.Matchers.containsInAnyOrder("PROJECT", "TEAM")));

        // A project-scoped reader sees only the budget of their project.
        mockMvc.perform(get("/api/v1/budgets")
                        .header("Authorization", projectOwnerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(projectBudget)));

        // ... and cannot read the team budget of the same organization.
        mockMvc.perform(get("/api/v1/budgets/{budgetId}", teamBudget)
                        .header("Authorization", projectOwnerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void wrongOrganizationIsAPrivacy404() throws Exception {
        var budgetId = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        mockMvc.perform(get("/api/v1/budgets/{budgetId}", budgetId)
                        .header("Authorization", foreignReaderBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void updateManagesTotalWithVersionCas() throws Exception {
        var budgetId = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "50.00000000", "150.00000000");

        mockMvc.perform(put("/api/v1/budgets/{budgetId}", budgetId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalAmount":"1200.00000000","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value("1200.00000000"))
                .andExpect(jsonPath("$.actualAmount").value("50.00000000"))
                .andExpect(jsonPath("$.committedAmount").value("150.00000000"))
                .andExpect(jsonPath("$.availableAmount").value("1000.00000000"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(budgetVersion(budgetId)).isEqualTo(1);
        assertThat(auditCount("BUDGET_TOTAL_CHANGED")).isEqualTo(1);

        // Stale version is a conflict.
        mockMvc.perform(put("/api/v1/budgets/{budgetId}", budgetId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalAmount":"1300.00000000","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void managementApiNeverWritesFinancialCounters() throws Exception {
        var budgetId = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "-50.00000000", "300.00000000");

        // The update contract only carries totalAmount + expectedVersion, so
        // counters seeded in the database survive every management mutation.
        mockMvc.perform(put("/api/v1/budgets/{budgetId}", budgetId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalAmount":"900.00000000","expectedVersion":0}
                                """))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=? AND org_id=?",
                String.class, budgetId, orgId)).isEqualTo("-50.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT committed_amount FROM budget WHERE id=? AND org_id=?",
                String.class, budgetId, orgId)).isEqualTo("300.00000000");
    }

    @Test
    void availableUsesExactDecimalFormula() throws Exception {
        // Seed a credit-driven negative actual: available = 1000 - (-50) - 200.
        var budgetId = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "-50.00000000", "200.00000000");
        mockMvc.perform(get("/api/v1/budgets/{budgetId}", budgetId)
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value("850.00000000"))
                .andExpect(jsonPath("$.overBudget").value(false));

        // Over-budget: total 500, actual 600 -> available -100, overBudget true.
        var overBudgetId = insertBudgetRow(orgId, periodId, "TEAM", teamId, "CNY",
                "500.00000000", "600.00000000", "0.00000000");
        mockMvc.perform(get("/api/v1/budgets/{budgetId}", overBudgetId)
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableAmount").value("-100.00000000"))
                .andExpect(jsonPath("$.overBudget").value(true));
    }

    @Test
    void permissionFailuresAreForbidden() throws Exception {
        // No grants at all: 403 before any resource lookup.
        var noGrantUserId = insertUser("budget-nogrant-" + System.nanoTime() + "@example.com");
        insertMember(orgId, noGrantUserId);
        var noGrantBearer = "Bearer " + tokens.issue(noGrantUserId, 7).token();
        mockMvc.perform(get("/api/v1/budgets")
                        .header("Authorization", noGrantBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // BUDGET_READ without BUDGET_MANAGE cannot mutate.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", readerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "1000.00000000")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Unauthenticated requests are rejected by the security chain.
        mockMvc.perform(get("/api/v1/budgets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void budgetManageRequiresFreshAuthorization() throws Exception {
        // The manager token is issued at the current security version.
        var oldJwt = tokens.issue(managerUserId, securityVersion(managerUserId)).token();

        // Revoke BUDGET_MANAGE and bump the security version, as the role
        // assignment service does for any authorization change.
        jdbc.update("""
                DELETE ra FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id
                WHERE ra.org_member_id=? AND r.code='BUDGET_MANAGER'
                """, managerMemberId);
        jdbc.update("UPDATE app_user SET security_version=security_version+1 WHERE id=?",
                managerUserId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // A sensitive mutation with the stale token must not succeed.
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", "Bearer " + oldJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "1000.00000000")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
    }

    @Test
    void createAuditsMinimalSecretFreeMetadata() throws Exception {
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROJECT", projectId, "CNY", "1000.00000000")))
                .andExpect(status().isCreated());

        var metadata = jdbc.queryForObject("""
                SELECT JSON_PRETTY(metadata_json) FROM audit_event
                WHERE event_type='BUDGET_CREATED' ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(metadata).isNotNull().doesNotContain("password", "token", "secret", "jwt");
    }

    private String createBody(String scopeType, long scopeId, String currency, String total) {
        return """
                {"billingPeriodId":"%d","scopeType":"%s","scopeId":"%d",
                 "currency":"%s","totalAmount":"%s"}
                """.formatted(periodId, scopeType, scopeId, currency, total);
    }
}