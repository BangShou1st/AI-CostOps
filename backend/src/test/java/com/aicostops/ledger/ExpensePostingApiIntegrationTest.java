package com.aicostops.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** HTTP contract and authentication checks for expense posting. */
@SpringBootTest
@AutoConfigureMockMvc
class ExpensePostingApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mvc;

    private long expenseId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('EXPENSE_POST','LEDGER_POST')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        expenseId = insertApprovedExpense("10.00000000");
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'EXPENSE_CLAIM', NULL, ?, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, expenseId, actorMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND expense_claim_id=?",
                Long.class, orgId, expenseId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '10.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, projectId);
        jdbc.update("UPDATE expense_claim SET current_allocation_decision_id=? WHERE id=?",
                decisionId, expenseId);
    }

    @Test
    void returnsStringIdsAndExpenseSource() throws Exception {
        mvc.perform(post("/api/v1/expenses/{expenseId}/post", expenseId)
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.sourceId").isString())
                .andExpect(jsonPath("$.sourceType").value("EXPENSE_CLAIM"))
                .andExpect(jsonPath("$.entries[0].sourceExpenseClaimId").isString())
                .andExpect(jsonPath("$.entries[0].amount").value("10.00000000"));
    }

    @Test
    void anonymousPostingIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/expenses/{expenseId}/post", expenseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
