package com.aicostops.e2e;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AIC-064 employee expense mainline over the HTTP boundary: an employee
 * submits an expense with evidence, finance approves it, the claim is
 * manually allocated to a project and the decision confirmed, the approved
 * claim posts into the immutable ledger, and the period reconciles clean
 * and closes. Every step is an authenticated API call.
 */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class E2eExpenseMainlineIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private static final String AUG_1 = "2026-08-01 00:00:00.000000";
    private static final String SEP_1 = "2026-09-01 00:00:00.000000";

    @Test
    void submitApproveAllocatePostReconcileCloseReachesClosedPeriod() throws Exception {
        grantFinance();
        seedEmployee();
        // M6 approve admission fences the claim's expense date behind an OPEN
        // covering BillingPeriod, so it must exist before approval lands.
        var periodId = augustPeriodId();

        // 1. Employee creates a DRAFT claim over the open August period.
        var expenseId = Long.parseLong(mockMvc.perform(post("/api/v1/expenses")
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "e2e-expense-create")
                        .contentType("application/json")
                        .content("{\"expenseDate\":\"2026-08-02\","
                                + "\"amount\":\"88.00000000\",\"currency\":\"CNY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll(".*\"id\":\"([0-9]+)\".*", "$1"));

        // 2. Evidence must exist before submission.
        mockMvc.perform(multipart("/api/v1/expenses/{expenseId}/evidence", expenseId)
                        .file(new MockMultipartFile("file", "invoice.pdf",
                                "application/pdf", "e2e-expense-receipt".getBytes(
                                        StandardCharsets.UTF_8)))
                        .param("expectedVersion", "0")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 3. Submit moves the claim to SUBMITTED with an approval case.
        mockMvc.perform(post("/api/v1/expenses/{expenseId}/submit", expenseId)
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", "e2e-expense-submit")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // 4. Finance approves; posting stays blocked until allocation lands.
        mockMvc.perform(post("/api/v1/expenses/{expenseId}/approve", expenseId)
                        .header("Authorization", financeBearer())
                        .header("Idempotency-Key", "e2e-expense-approve")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.postingReady").value(false));

        // 5. Manual allocation of the full amount to the fixture project.
        // The decision response embeds lines[].id, so parse the FIRST "id"
        // occurrence (repo-wide convention, cf. decisionIdFrom in
        // AllocationDecisionApiIntegrationTest); a greedy regex would grab the
        // line row's id and 404 on confirm once sequences diverge.
        var decisionResponse = mockMvc.perform(post(
                        "/api/v1/expenses/{expenseId}/allocation-decisions/manual", expenseId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "e2e-expense-alloc")
                        .contentType("application/json")
                        .content("{\"lines\":[{\"allocatedAmount\":\"88.00000000\","
                                + "\"currency\":\"CNY\",\"projectId\":\"" + projectId + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var decisionId = firstStringId(decisionResponse);
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", decisionId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "e2e-expense-confirm-alloc"))
                .andExpect(status().isOk());

        // 6. Post the approved claim into the immutable ledger.
        mockMvc.perform(post("/api/v1/expenses/{expenseId}/post", expenseId)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].amount").value("88.00000000"))
                .andExpect(jsonPath("$.entries[0].currency").value("CNY"));
        assertThatEqual("SELECT status FROM expense_claim WHERE id=?", "POSTED", expenseId);

        // 7. The period reconciles cleanly and closes over the posted claim.
        mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"billingPeriodId\":\"" + periodId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.discrepancyCount").value(0));
        mockMvc.perform(post("/api/v1/billing-periods/{periodId}/close", periodId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStatus").value("CLOSED"))
                .andExpect(jsonPath("$.checks.length()").value(7));
        assertThatEqual("SELECT status FROM billing_period WHERE id=?", "CLOSED", periodId);
    }

    // -- fixtures --------------------------------------------------------------

    private void grantFinance() {
        createPermissionRole("E2E_FINANCE_ALL", List.of(
                "EXPENSE_REVIEW", "EXPENSE_POST", "COST_READ", "ALLOCATION_READ",
                "ALLOCATION_EDIT", "ALLOCATION_CONFIRM", "LEDGER_POST", "LEDGER_READ",
                "RECONCILIATION_RUN", "RECONCILIATION_READ", "PERIOD_READ", "PERIOD_CLOSE"));
        assign("E2E_FINANCE_ALL", "ORG", orgId);
    }

    /**
     * Seeds an employee actor with the OWN permission set required by the
     * M4 admission rules (create/submit/evidence), mirroring the expense
     * module test fixture.
     */
    private void seedEmployee() {
        var suffix = "e2e-exp-" + System.nanoTime();
        // custom roles live for one test only: deleteCustomRoles() recreates a
        // clean catalog before every test and removes E2E roles after cleanup.
        createPermissionRole("E2E_EXP_EMPLOYEE", List.of(
                "EXPENSE_CREATE_OWN", "EXPENSE_READ_OWN", "EXPENSE_SUBMIT_OWN",
                "EVIDENCE_UPLOAD_OWN"));
        employeeUserId = insertUser(suffix + "@example.com");
        employeeMemberId = insertMember(orgId, employeeUserId);
        assign("E2E_EXP_EMPLOYEE", "ORG", orgId, employeeMemberId);
    }

    private long employeeUserId;
    private long employeeMemberId;

    private String employeeBearer() {
        return "Bearer " + tokens.issue(employeeUserId, 7).token();
    }

    private String financeBearer() {
        return "Bearer " + tokens.issue(financeUserId, 7).token();
    }

    private long augustPeriodId() {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_1, SEP_1);
        return jdbc.queryForObject(
                "SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
    }

    private void assertThatEqual(String query, String expected, long id) {
        var actual = jdbc.queryForObject(query, String.class, id);
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but was " + actual
                    + " for [" + query + "] id=" + id);
        }
    }

    /** First {@code "id":"<digits>"} occurrence — the decision id, not a line's. */
    private static long firstStringId(String response) {
        var start = response.indexOf("\"id\":\"") + "\"id\":\"".length();
        var end = response.indexOf('"', start);
        return Long.parseLong(response.substring(start, end));
    }
}
