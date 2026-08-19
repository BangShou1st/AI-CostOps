package com.aicostops.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract checks for correction IDs, money strings and required idempotency. */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerCorrectionApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ProviderChargePostingService providerPostings;

    private long targetEntryId;
    private long correctionPeriodId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('LEDGER_POST','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-02-01 00:00:00.000000','2026-03-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        correctionPeriodId = jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?",
                Long.class, orgId);
        var chargeId = insertCharge("6.00000000");
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '6.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, projectId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        var posted = providerPostings.post(new AuthenticatedUser(actorUserId, 7), chargeId,
                new PostSourceCommand(java.util.List.of()));
        targetEntryId = posted.entries().getFirst().id();
    }

    @Test
    void correctionResponseUsesStringIdsAndDecimalMoney() throws Exception {
        var body = """
                {
                  "targetEntryId":"%d",
                  "correctionPeriodId":"%d",
                  "mode":"REPLACE",
                  "reasonCode":"ALLOCATION_ERROR",
                  "replacement":{"amount":"10.00000000","currency":"CNY","projectId":null,"costCenterId":null,"teamId":"%d"}
                }
                """.formatted(targetEntryId, correctionPeriodId, teamId);

        mvc.perform(post("/api/v1/ledger/corrections")
                .header("Authorization", bearer())
                .header("Idempotency-Key", "api-correction-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionGroupId").isString())
                .andExpect(jsonPath("$.posting.id").isString())
                .andExpect(jsonPath("$.posting.sourceType").value("CORRECTION"))
                .andExpect(jsonPath("$.posting.entries[0].amount").value("-6.00000000"))
                .andExpect(jsonPath("$.posting.entries[1].amount").value("10.00000000"));
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeCorrection() throws Exception {
        mvc.perform(post("/api/v1/ledger/corrections")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
