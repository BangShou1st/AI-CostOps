package com.aicostops.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP shape for scoped Ledger reads and provider lineage. */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerLineageApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mvc;

    private long chargeId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('LEDGER_POST','LEDGER_READ')
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
        var decisionId = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
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
    void listPostingAndEntryLineageUseStringIdsAndDecimalStrings() throws Exception {
        var posting = mvc.perform(post("/api/v1/costs/charges/{chargeFactId}/post", chargeId)
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var postingId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(posting).get("id").asText();
        var entryId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(posting).get("entries").get(0).get("id").asText();

        mvc.perform(get("/api/v1/ledger/postings/{postingId}", postingId)
                .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.visibleTotalAmount").value("10.00000000"));
        mvc.perform(get("/api/v1/ledger/entries/{entryId}", entryId)
                .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.id").isString())
                .andExpect(jsonPath("$.entry.amount").value("10.00000000"))
                .andExpect(jsonPath("$.lineage.chargeFactId").isString())
                .andExpect(jsonPath("$.lineage.rawProviderRecordId").isString())
                .andExpect(jsonPath("$.lineage.expenseClaimId").doesNotExist());
    }

    @Test
    void listEndpointsRequireLedgerRead() throws Exception {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        mvc.perform(get("/api/v1/ledger/postings").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void ledgerListRejectsInvalidPaginationWithValidationProblemDetail() throws Exception {
        for (var path : List.of("/api/v1/ledger/postings", "/api/v1/ledger/entries")) {
            for (var invalid : List.of(new String[] {"page", "-1"},
                    new String[] {"size", "0"}, new String[] {"size", "201"})) {
                mvc.perform(get(path).header("Authorization", bearer())
                        .queryParam(invalid[0], invalid[1]))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.type")
                                .value("https://aicostops.dev/problems/validation-failed"));
            }
        }
    }
}
