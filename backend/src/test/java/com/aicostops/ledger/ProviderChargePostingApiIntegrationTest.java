package com.aicostops.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract checks for provider posting. */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderChargePostingApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mvc;

    private long chargeId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code='LEDGER_POST'
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        chargeId = insertCharge("10.00000000");
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, chargeId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '10.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, projectId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
    }

    @Test
    void returnsStringIdsAndAllowsNoBudgetPosting() throws Exception {
        mvc.perform(post("/api/v1/costs/charges/{chargeFactId}/post", chargeId)
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.sourceId").isString())
                .andExpect(jsonPath("$.allocationDecisionId").isString())
                .andExpect(jsonPath("$.entries[0].amount").value("10.00000000"))
                .andExpect(jsonPath("$.entries[0].budgetId").doesNotExist());
    }

    @Test
    void anonymousPostingIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/costs/charges/{chargeFactId}/post", chargeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
