package com.aicostops.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ProviderChargePostingService postings;

    private long periodId;

    @BeforeEach
    void reconciliationFixture() {
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'LEDGER_POST','RECONCILIATION_READ','RECONCILIATION_RUN','RECONCILIATION_RESOLVE')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        periodId = jdbc.queryForObject(
                "SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
    }

    @Test
    void runUsesServerToleranceAndExactMatchCreatesNoCase() throws Exception {
        var chargeId = insertCharge("10.00000000");
        confirmAllocationAndPost(chargeId, "10.00000000");

        mvc.perform(post("/api/v1/reconciliation-runs")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"billingPeriodId":"%d","toleranceAmount":"999999.00000000"}
                        """.formatted(periodId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.billingPeriodId").value(Long.toString(periodId)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.toleranceAmount").value("0.00000000"))
                .andExpect(jsonPath("$.basisHash").isString())
                .andExpect(jsonPath("$.summary.totalKeys").value(1))
                .andExpect(jsonPath("$.summary.matchedCount").value(1))
                .andExpect(jsonPath("$.summary.discrepancyCount").value(0));

        var runId = jdbc.queryForObject(
                "SELECT MAX(id) FROM reconciliation_run WHERE org_id=?", Long.class, orgId);
        assertNoCases(runId);
    }

    @Test
    void materialMismatchCreatesCaseAndResolutionDoesNotMutateLedger() throws Exception {
        var postedChargeId = insertCharge("10.00000000");
        confirmAllocationAndPost(postedChargeId, "10.00000000");
        insertCharge("5.00000000");

        mvc.perform(post("/api/v1/reconciliation-runs")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"billingPeriodId\":\"" + periodId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.discrepancyCount").value(1));

        var runId = jdbc.queryForObject(
                "SELECT MAX(id) FROM reconciliation_run WHERE org_id=?", Long.class, orgId);
        var caseId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_case WHERE org_id=? AND reconciliation_run_id=?",
                Long.class, orgId, runId);

        mvc.perform(get("/api/v1/reconciliation-cases")
                .header("Authorization", bearer())
                .param("runId", Long.toString(runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(caseId)))
                .andExpect(jsonPath("$.items[0].caseType").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.items[0].externalAmount").value("15.00000000"))
                .andExpect(jsonPath("$.items[0].internalAmount").value("10.00000000"))
                .andExpect(jsonPath("$.items[0].differenceAmount").value("-5.00000000"));

        var ledgerBefore = ledgerTotal();
        mvc.perform(post("/api/v1/reconciliation-cases/{caseId}/investigate", caseId)
                .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));

        mvc.perform(post("/api/v1/reconciliation-cases/{caseId}/resolve", caseId)
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reasonCode":"TIMING_DIFFERENCE","resolutionNote":"Provider export contains an unposted charge."}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.reasonCode").value("TIMING_DIFFERENCE"));

        org.assertj.core.api.Assertions.assertThat(ledgerTotal())
                .isEqualByComparingTo(ledgerBefore);
    }

    @Test
    void runRejectsNonOpenPeriod() throws Exception {
        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", periodId);
        mvc.perform(post("/api/v1/reconciliation-runs")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"billingPeriodId\":\"" + periodId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_OPEN"));
    }

    private void confirmAllocationAndPost(long chargeId, String amount) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, ?,'CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, amount, projectId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        postings.post(new AuthenticatedUser(actorUserId, 7), chargeId,
                new PostSourceCommand(List.of()));
    }

    private void assertNoCases(long runId) {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_case WHERE reconciliation_run_id=?",
                Long.class, runId)).isZero();
    }

    private BigDecimal ledgerTotal() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM ledger_entry WHERE org_id=?",
                BigDecimal.class, orgId);
    }
}
