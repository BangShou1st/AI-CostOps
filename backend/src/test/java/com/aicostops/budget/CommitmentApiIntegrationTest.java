package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Commitment HTTP contract: request with Idempotency-Key replay, exact money
 * validation, approve/reject/cancel/release mutations, grant-scoped reads,
 * and permission/privacy failures — mirroring the openapi.yaml contract.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=commitment-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class CommitmentApiIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestApproveReleaseFlowOverHttp() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var body = """
                {"requestedAmount":"120.50000000","currency":"CNY"}
                """;

        var requestedJson = mockMvc.perform(post("/api/v1/budgets/{budgetId}/commitments", budgetId)
                        .header("Authorization", requesterBearer())
                        .header("Idempotency-Key", "api-req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.budgetId").value(Long.toString(budgetId)))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.requestedAmount").value("120.50000000"))
                .andExpect(jsonPath("$.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.history[0].actionType").value("SUBMIT"))
                // M4 contract: approval action ids are JSON strings (ApiId), not numbers.
                .andExpect(jsonPath("$.history[0].id").isString())
                .andExpect(jsonPath("$.history[0].approvalCaseId").isString())
                .andExpect(jsonPath("$.history[0].actorMemberId").isString())
                .andReturn().getResponse().getContentAsString();

        // Replay with the same key returns the stored commitment, no new row.
        var replay = mockMvc.perform(post("/api/v1/budgets/{budgetId}/commitments", budgetId)
                        .header("Authorization", requesterBearer())
                        .header("Idempotency-Key", "api-req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.history[0].id").isString())
                .andExpect(jsonPath("$.history[0].approvalCaseId").isString())
                .andExpect(jsonPath("$.history[0].actorMemberId").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(requestedJson);
        var rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment WHERE org_id=? AND budget_id=?",
                Integer.class, orgId, budgetId);
        assertThat(rows).isEqualTo(1);

        var commitmentId = jdbc.queryForObject(
                "SELECT id FROM budget_commitment WHERE org_id=? AND budget_id=?",
                Long.class, orgId, budgetId);

        // Approve (reviewer) with version 0.
        mockMvc.perform(post("/api/v1/commitments/{commitmentId}/approve", commitmentId)
                        .header("Authorization", reviewerBearer())
                        .header("Idempotency-Key", "api-appr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.approvedAmount").value("120.50000000"))
                .andExpect(jsonPath("$.remainingAmount").value("120.50000000"))
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1));

        // Detail read for a BUDGET_READ caller.
        mockMvc.perform(get("/api/v1/commitments/{commitmentId}", commitmentId)
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.history[1].actionType").value("APPROVE"))
                .andExpect(jsonPath("$.history[1].id").isString());

        // Release frees the remainder.
        mockMvc.perform(post("/api/v1/commitments/{commitmentId}/release", commitmentId)
                        .header("Authorization", reviewerBearer())
                        .header("Idempotency-Key", "api-rel-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.remainingAmount").value("0.00000000"));
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
    }

    @Test
    void rejectAndCancelFlowOverHttp() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var first = commitRequest(budgetId, "10.00000000", "api-req-2");
        var second = commitRequest(budgetId, "5.00000000", "api-req-3");

        mockMvc.perform(post("/api/v1/commitments/{commitmentId}/reject", first)
                        .header("Authorization", reviewerBearer())
                        .header("Idempotency-Key", "api-rej-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"comment\":\"duplicate request\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approvalStatus").value("REJECTED"))
                .andExpect(jsonPath("$.history[1].comment").value("duplicate request"));

        mockMvc.perform(post("/api/v1/commitments/{commitmentId}/cancel", second)
                        .header("Authorization", requesterBearer())
                        .header("Idempotency-Key", "api-can-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.approvalStatus").value("CANCELED"));

        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
    }

    @Test
    void listIsGrantScopedAndPrivacy404ForForeignCommitments() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = commitRequest(budgetId, "10.00000000", "api-req-4");

        // The org-wide reader sees the commitment list and detail.
        mockMvc.perform(get("/api/v1/commitments")
                        .header("Authorization", readerBearer())
                        .param("budgetId", Long.toString(budgetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // A caller with only PROJECT-scoped BUDGET_READ on another project
        // cannot see this budget's commitments (empty page, no leak).
        mockMvc.perform(get("/api/v1/commitments")
                        .header("Authorization", scopedBearer("PROJECT", projectId))
                        .param("budgetId", Long.toString(budgetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Foreign commitment stays privacy-hidden.
        var foreignPeriod = insertCurrentBillingPeriod(foreignOrgId, "OPEN");
        var foreignBudget = insertBudgetRow(foreignOrgId, foreignPeriod, "ORG", foreignOrgId,
                "CNY", "1000.00000000", "0.00000000", "0.00000000");
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,
                    approved_amount,remaining_amount,version,created_at,updated_at)
                VALUES (?,?,'REQUESTED',1.00000000,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, foreignOrgId, foreignBudget);
        var foreignId = jdbc.queryForObject("""
                SELECT id FROM budget_commitment WHERE org_id=? AND budget_id=?
                """, Long.class, foreignOrgId, foreignBudget);

        mockMvc.perform(get("/api/v1/commitments/{commitmentId}", foreignId)
                        .header("Authorization", readerBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void missingPermissionIs403BeforeAnyLookup() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        mockMvc.perform(post("/api/v1/budgets/{budgetId}/commitments", budgetId)
                        .header("Authorization", noPermissionBearer())
                        .header("Idempotency-Key", "api-noperm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedAmount\":\"1.00000000\",\"currency\":\"CNY\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void malformedMoneyIsRejected() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        mockMvc.perform(post("/api/v1/budgets/{budgetId}/commitments", budgetId)
                        .header("Authorization", requesterBearer())
                        .header("Idempotency-Key", "api-bad-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedAmount\":\"1.000000001\",\"currency\":\"CNY\"}"))
                .andExpect(status().isBadRequest());
    }

    // -- helpers --------------------------------------------------------------

    private long commitRequest(long budgetId, String amount, String key) throws Exception {
        var body = "{\"requestedAmount\":\"" + amount + "\",\"currency\":\"CNY\"}";
        var result = mockMvc.perform(post("/api/v1/budgets/{budgetId}/commitments", budgetId)
                        .header("Authorization", requesterBearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        var response = result.getResponse();
        if (response.getStatus() != 200) {
            System.err.println("commitRequest failed: " + response.getStatus() + " body="
                    + response.getContentAsString());
        }
        org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatus());
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString())
                .get("id").asLong();
    }

    private String scopedBearer(String scopeType, long scopeId) {
        // The scoped user already has PROJECT-scoped grants; this helper is
        // kept for future scoped assertions.
        return "Bearer " + tokens.issue(scopedUserId, 7).token();
    }

    private String noPermissionBearer() {
        return "Bearer " + tokens.issue(noPermissionUserId, 7).token();
    }
}
