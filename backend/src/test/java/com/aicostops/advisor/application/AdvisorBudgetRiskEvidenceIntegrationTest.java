package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.providerhub.application.ControlPlaneFixtureSupport;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frozen BUDGET_RISK evidence over real MySQL, re-checked after the budget-risk query service was
 * extracted (M18 V3): the snapshot still carries active reservations in immediate exposure only,
 * still projects period end from the scope's own forecast grain, and still refuses to substitute
 * the whole-organization forecast for a scope that has none.
 *
 * <p>The reservation row carries full Gateway lineage in production; these assertions concern its
 * monetary contribution only, so the parent rows are omitted with a session-level FK exemption
 * inside this rolled-back test transaction (the table CHECK constraints still apply).
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Transactional
@Tag("integration")
class AdvisorBudgetRiskEvidenceIntegrationTest extends ControlPlaneFixtureSupport {
    @org.springframework.beans.factory.annotation.Autowired
    private com.aicostops.intelligence.application.CostAnalysisInputService inputs;


    @Autowired
    private MockMvc mockMvc;

    /**
     * The service resolves "yesterday" from the injected clock (system UTC), so the fixture must use
     * the same clock. A local-time {@code LocalDate.now()} diverges for the hours where the local
     * date is already ahead of UTC, which would silently point at the wrong analysis run.
     */
    @Autowired
    private java.time.Clock clock;

    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long projectA;
    private long teamT;
    private long modelA;
    private long runId;

    @BeforeEach
    void setUp() {
        flushRedis();
        organizationId = insertOrganization("Risk Evidence Org", "risk-evidence-org");
        managerUserId = insertUser("risk-evidence@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        projectA = insertProject(organizationId, "proj-a");
        teamT = insertTeam(organizationId, "team-t");
        var logicalA = insertGlobalLogicalModel("re-model-a");
        modelA = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalA, "re-model-a");
        createPermissionRole("RE_MANAGER", List.of("AI_ADVISOR_MANAGE", "AI_ADVISOR_USE"));
        assign(managerMemberId, "RE_MANAGER", "ORG", organizationId);
        runId = insertRun();
        flushRedis();
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
    }

    @Test
    void reservationsEnterImmediateExposureOnlyAndScopeUsesItsOwnForecast() throws Exception {
        var periodId = openPeriod(organizationId);
        var budgetId = insertBudget(organizationId, periodId, "PROJECT", projectA, "1000.00", "100.00",
                "50.00");
        insertReservation(budgetId, "30.00000000");
        // The scope's own forecast grain is the only future usage admitted.
        insertForecast("PROJECT", "project:" + projectA, "200.00");

        var risk = riskBlock(requestExplanation("BUDGET_RISK", budgetId));
        assertEquals("PROJECT:" + projectA, risk.read("$.scope"));
        // Amounts keep the frozen DECIMAL(20,8) decimal-string form, including trailing zeros.
        assertEquals("100.00000000", risk.read("$.actual"));
        assertEquals("50.00000000", risk.read("$.committed"));
        assertEquals("30.00000000", risk.read("$.reservations"));
        assertEquals("200.00000000", risk.read("$.forecastFuture"));
        assertTrue(risk.forecastAvailable());
        // immediate = actual + committed + reservations
        assertEquals("180.00000000", risk.read("$.immediate"));
        // projected = actual + committed + forecast future usage; reservations never re-enter it.
        assertEquals("350.00000000", risk.read("$.projected"));
        assertEquals("1000.00000000", risk.read("$.total"));
        assertEquals("LOW", risk.read("$.risk"));
    }

    @Test
    void scopeWithoutItsOwnForecastNeverReceivesTheOrganizationForecast() throws Exception {
        var periodId = openPeriod(organizationId);
        var budgetId = insertBudget(organizationId, periodId, "TEAM", teamT, "100.00", "10.00", "5.00");
        // Only an ORGANIZATION grain exists; a TEAM scope must project zero future usage.
        insertForecast("ORGANIZATION", "org:" + organizationId, "9000.00");

        var risk = riskBlock(requestExplanation("BUDGET_RISK", budgetId));
        assertEquals("TEAM:" + teamT, risk.read("$.scope"));
        assertEquals("0.00000000", risk.read("$.reservations"));
        // Missing scoped evidence is unavailable; zero is only the calculation fallback.
        assertEquals("0", risk.read("$.forecastFuture"));
        assertFalse(risk.forecastAvailable());
        assertEquals("15.00000000", risk.read("$.immediate"));
        assertEquals("15.00000000", risk.read("$.projected"));
        assertEquals("LOW", risk.read("$.risk"));
    }

    @Test
    void foreignOrganizationBudgetIsRejectedBeforeAnyEvidenceIsBuilt() throws Exception {
        var foreignOrg = insertOrganization("Risk Evidence Foreign", "risk-evidence-foreign");
        var foreignPeriod = openPeriod(foreignOrg);
        var foreignProject = insertProject(foreignOrg, "proj-foreign");
        var foreignBudget = insertBudget(foreignOrg, foreignPeriod, "PROJECT", foreignProject, "100.00",
                "0.00", "0.00");

        mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"subjectType\":\"BUDGET_RISK\",\"subjectId\":" + foreignBudget + "}"))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM advisor_evidence_snapshot"
                + " WHERE org_id=?", Integer.class, organizationId));
    }

    /** Reads the frozen risk block the model would receive from the persisted snapshot. */
    private RiskBlock riskBlock(long jobId) {
        var summary = jdbc.queryForObject("SELECT CAST(summary_json AS CHAR) FROM advisor_evidence_snapshot"
                + " WHERE job_id=? AND org_id=?", String.class, jobId, organizationId);
        return new RiskBlock(JsonPath.read(summary, "$.budgetRiskSummary"));
    }

    private record RiskBlock(String json) {
        boolean forecastAvailable() {
            return JsonPath.read(json, "$.forecastAvailable");
        }
        String read(String path) {
            return JsonPath.read(json, path);
        }
    }

    private long requestExplanation(String subjectType, long subjectId) throws Exception {
        var body = mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"subjectType\":\"" + subjectType + "\",\"subjectId\":" + subjectId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void putProfile(long providerModelId, long projectId, String scopeType, long scopeId,
            String budgetMode) {
        try {
            mockMvc.perform(put("/api/v1/ai-advisor/profile")
                            .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                            .content("{\"providerModelId\":" + providerModelId + ",\"projectId\":" + projectId
                                    + ",\"financialScopeType\":\"" + scopeType + "\",\"financialScopeId\":"
                                    + scopeId + ",\"budgetEnforcementMode\":\"" + budgetMode + "\"}"))
                    .andExpect(status().isOk());
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor profile fixture failed", ex);
        }
    }

    private long insertRun() {
        // Same clock as the service: the risk lookup resolves yesterday from the injected clock.
        var yesterday = LocalDate.now(clock).minusDays(1);
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,"
                + "created_at) VALUES (?,?,'USD',?,'COMPLETED',UTC_TIMESTAMP(6))", organizationId, yesterday,
                com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        jdbc.update("UPDATE cost_intelligence_run SET input_fingerprint=? WHERE org_id=? AND run_version=?",
                inputs.currentFingerprint(organizationId, "USD", yesterday, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION),
                organizationId, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        return jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=?", Long.class,
                organizationId);
    }

    private void insertForecast(String scopeType, String scopeKey, String projected) {
        jdbc.update("INSERT INTO cost_forecast_snapshot(org_id,run_id,scope_type,scope_key,currency,"
                + "projected_amount,method,history_bucket_count,confidence,observed_through,created_at)"
                + " VALUES (?,?,?,?,'USD',?,'DAMPED_HOLT',20,'HIGH',CURDATE(),UTC_TIMESTAMP(6))",
                organizationId, runId, scopeType, scopeKey, new BigDecimal(projected));
    }

    private long openPeriod(long orgId) {
        jdbc.update("INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at)"
                + " VALUES (?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY),'OPEN',"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
        return jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
    }

    private long insertBudget(long orgId, long periodId, String scopeType, long scopeId, String total,
            String actual, String committed) {
        jdbc.update("INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,"
                + "actual_amount,committed_amount,status,created_at,updated_at)"
                + " VALUES (?,?,?,?, 'USD',?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                orgId, periodId, scopeType, scopeId, new BigDecimal(total), new BigDecimal(actual),
                new BigDecimal(committed));
        return jdbc.queryForObject("SELECT id FROM budget WHERE org_id=? AND scope_type=? AND scope_id=?",
                Long.class, orgId, scopeType, scopeId);
    }

    private void insertReservation(long budgetId, String amount) {
        var periodId = jdbc.queryForObject("SELECT billing_period_id FROM budget WHERE id=?", Long.class,
                budgetId);
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("""
                    INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,billing_period_id,
                      budget_id,financial_scope_type,financial_scope_id,currency,reserved_amount,
                      commitment_id,commitment_backed_amount,status,version,expires_at,created_at,updated_at)
                    VALUES (?,1,1,?,?,'PROJECT',?,'USD',?,NULL,0,'ACTIVE',0,
                      DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, organizationId, periodId, budgetId, projectA, new BigDecimal(amount));
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}
