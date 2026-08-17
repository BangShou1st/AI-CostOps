package com.aicostops.expense;

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
 * Employee expense HTTP contract: idempotent DRAFT creation, exact money
 * validation, optimistic-version PUT, privacy 404s, and permission failures.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ExpenseApiIntegrationTest extends ExpenseTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createReturnsDraftAndReplaysIdempotently() throws Exception {
        var body = """
                {"expenseDate":"2026-08-01","amount":"100.00000000","currency":"CNY"}
                """;

        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "api-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.claimantMemberId").value(Long.toString(employeeMemberId)))
                .andExpect(jsonPath("$.amount").value("100.00000000"))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.postingReady").value(false))
                .andExpect(jsonPath("$.canEdit").value(true));

        var replayed = mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "api-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andReturn().getResponse().getContentAsString();
        var one = jdbc.queryForObject(
                "SELECT COUNT(*) FROM expense_claim WHERE claimant_member_id=?",
                Integer.class, employeeMemberId);
        assertThat(one).isEqualTo(1);
        // stored replay body must contain the id
        org.assertj.core.api.Assertions.assertThat(replayed).contains("\"status\":\"DRAFT\"");
    }

    @Test
    void createRejectsBadMoneyAndBadDate() throws Exception {
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "api-create-bad1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"2026-08-01","amount":"100.00","currency":"CNY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "api-create-bad2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"not-a-date","amount":"100.00000000","currency":"CNY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "api-create-bad3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"2026-08-01","amount":"100.00000000","currency":"C"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createRejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"2026-08-01","amount":"100.00000000","currency":"CNY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
    }

    @Test
    void getListAndForeignPrivacy404() throws Exception {
        var expenseId = insertExpenseDraft();
        mockMvc.perform(get("/api/v1/expenses/{expenseId}", expenseId)
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(expenseId)));

        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        var foreignUserId = insertUser("api-foreign-" + System.nanoTime() + "@example.com");
        var foreignClaimant = insertMember(orgId, foreignUserId);
        var otherExpenseId = insertExpenseDraftFor(orgId, foreignClaimant,
                "50.00000000", "CNY", "DRAFT");
        mockMvc.perform(get("/api/v1/expenses/{expenseId}", otherExpenseId)
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void putEditsBodyAndConflictsOnStaleVersion() throws Exception {
        var expenseId = insertExpenseDraft();
        mockMvc.perform(put("/api/v1/expenses/{expenseId}", expenseId)
                        .header("Authorization", employeeBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"2026-08-02","amount":"120.00000000",
                                 "currency":"USD","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.amount").value("120.00000000"));

        mockMvc.perform(put("/api/v1/expenses/{expenseId}", expenseId)
                        .header("Authorization", employeeBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expenseDate":"2026-08-03","amount":"130.00000000",
                                 "currency":"CNY","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void unauthenticatedAndUnauthorizedAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/expenses"))
                .andExpect(status().isUnauthorized());

        // A fresh user with no grants reaches the endpoint but fails requireOrg.
        var noGrantUserId = insertUser("no-grant-" + System.nanoTime() + "@example.com");
        insertMember(orgId, noGrantUserId);
        var noGrantBearer = "Bearer " + tokens.issue(noGrantUserId, 7).token();
        mockMvc.perform(get("/api/v1/expenses")
                        .header("Authorization", noGrantBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}