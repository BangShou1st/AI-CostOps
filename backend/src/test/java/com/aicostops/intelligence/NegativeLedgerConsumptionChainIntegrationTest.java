package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.providerhub.application.ControlPlaneFixtureSupport;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Negative-ledger consumption chain over real MySQL (U1 regression guard).
 *
 * <p>A short reversal-dominated history (three observed days of -1 CNY each) must never be
 * persisted as a negative future-usage estimate, and the budget-risk/Advisor consumers must not
 * fail on such a snapshot. "No forecast" and "future cost is zero" stay distinct: the snapshot is
 * absent, the risk answer reports forecastAvailable=false, and the signed ledger facts themselves
 * are untouched.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET })
@AutoConfigureMockMvc
@Tag("integration")
class NegativeLedgerConsumptionChainIntegrationTest extends ControlPlaneFixtureSupport {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private com.aicostops.intelligence.application.CostIntelligenceAnalysisService analysis;
    @Autowired
    private com.aicostops.intelligence.application.CostAnalysisInputService inputs;
    @Autowired
    private java.time.Clock clock;

    private static final LocalDate ANALYSIS_DATE = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);

    private long org;
    private long user;
    private long member;
    private long project;
    private long accountId;

    @BeforeEach
    void setUp() {
        flushRedis();
        var suffix = java.util.UUID.randomUUID().toString();
        org = insertOrganization("Neg Ledger Org", "neg-ledger-" + suffix);
        user = insertUser("neg-ledger-" + suffix + "@example.test");
        member = insertMember(org, user);
        project = insertProject(org, "neg-proj");
        accountId = insertAccount(org, "CUSTOM_OPENAI_COMPATIBLE", "Neg Ledger Account");
        createPermissionRole("NEG_LEDGER_" + suffix.substring(0, 8),
                List.of("COST_READ", "BUDGET_READ", "COST_COVERAGE_MANAGE", "AI_ADVISOR_USE", "AI_ADVISOR_MANAGE"));
        assign(member, "NEG_LEDGER_" + suffix.substring(0, 8), "ORG", org);
        insertNegativeDailySeries();
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM advisor_evidence_snapshot WHERE org_id=?", org);
        jdbc.update("DELETE FROM advisor_explanation WHERE org_id=?", org);
        jdbc.update("DELETE FROM advisor_inference_attempt WHERE org_id=?", org);
        jdbc.update("DELETE FROM advisor_inference_job WHERE org_id=?", org);
        jdbc.update("UPDATE gateway_credential SET predecessor_credential_id=NULL, advisor_profile_id=NULL"
                + " WHERE org_id=?", org);
        jdbc.update("DELETE FROM gateway_credential_model WHERE org_id=?", org);
        jdbc.update("DELETE FROM gateway_credential WHERE org_id=?", org);
        jdbc.update("DELETE FROM advisor_profile WHERE org_id=?", org);
        jdbc.update("DELETE FROM savings_recommendation WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_forecast_snapshot WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_anomaly WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_intelligence_run WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_history_coverage WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", org);
        jdbc.update("DELETE FROM audit_event WHERE org_id=?", org);
        jdbc.update("DELETE FROM ledger_entry WHERE org_id=?", org);
        jdbc.update("DELETE FROM ledger_posting WHERE org_id=?", org);
        jdbc.update("DELETE FROM reconciliation_adjustment WHERE org_id=?", org);
        jdbc.update("DELETE FROM reconciliation_case WHERE org_id=?", org);
        jdbc.update("DELETE FROM reconciliation_run WHERE org_id=?", org);
        jdbc.update("DELETE FROM gateway_settlement WHERE org_id=?", org);
        jdbc.update("DELETE FROM budget WHERE org_id=?", org);
        jdbc.update("DELETE FROM billing_period WHERE org_id=?", org);
        jdbc.update("DELETE FROM provider_account WHERE org_id=?", org);
        jdbc.update("DELETE FROM project WHERE org_id=?", org);
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", member);
        jdbc.update("DELETE FROM service_identity WHERE org_id=?", org);
        jdbc.update("DELETE FROM role_permission WHERE role_id IN (SELECT id FROM `role` WHERE code LIKE 'NEG_LEDGER_%')");
        jdbc.update("DELETE FROM `role` WHERE code LIKE 'NEG_LEDGER_%'");
        jdbc.update("DELETE FROM organization_member WHERE id=?", member);
        jdbc.update("DELETE FROM organization WHERE id=?", org);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
        flushRedis();
    }

    /**
     * Three consecutive observed days of -1 CNY each in the effective POSTED ledger lineage.
     * Negative amounts are legal only through append-only correction/reconciliation-adjustment
     * lineage: gateway_settlement.enforced CHECK keeps settlement amounts non-negative, so a
     * reversal-dominated history here uses RECONCILIATION_ADJUSTMENT postings (amount <> 0, sign
     * free) — exactly the production path that produced the U1 negative net.
     */
    private void insertNegativeDailySeries() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at)"
                    + " VALUES (?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 DAY),"
                    + "DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 20 DAY),'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org);
            var periodId = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? LIMIT 1",
                    Long.class, org);
            jdbc.update("INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,"
                    + "tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,finished_at,"
                    + "created_at,updated_at)"
                    + " VALUES (?,?,'COMPLETED','fixture-v1',0,REPEAT('0',64),JSON_OBJECT(),?,UTC_TIMESTAMP(6),"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org, periodId, member);
            var runId = jdbc.queryForObject(
                    "SELECT id FROM reconciliation_run WHERE org_id=? ORDER BY id DESC LIMIT 1", Long.class, org);
            jdbc.update("INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,"
                    + "currency,case_type,external_amount,internal_amount,difference_amount,"
                    + "external_row_count,internal_row_count,status,reason_code,resolution_note,"
                    + "resolved_by_member_id,resolved_at,created_at,updated_at)"
                    + " VALUES (?,?,?,'CNY','AMOUNT_MISMATCH',5,4,-1,1,1,'RESOLVED','TEST','fixture',"
                    + "?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org, runId, accountId, member);
            var caseId = jdbc.queryForObject(
                    "SELECT id FROM reconciliation_case WHERE org_id=? ORDER BY id DESC LIMIT 1", Long.class, org);
            jdbc.update("INSERT INTO reconciliation_adjustment(org_id,reconciliation_run_id,reconciliation_case_id,"
                    + "adjustment_key,adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,"
                    + "created_by_member_id,reason_code,reason_note,created_at)"
                    + " VALUES (?,?,?,?, 'CASE_FULL',?,'CNY',-1.00000000,?,?,"
                    + "'TEST','negative net fixture',UTC_TIMESTAMP(6))",
                    org, runId, caseId, "neg-adj-" + org, accountId, periodId, member);
            var adjustmentId = jdbc.queryForObject(
                    "SELECT id FROM reconciliation_adjustment WHERE org_id=? AND adjustment_key=?",
                    Long.class, org, "neg-adj-" + org);
            for (var i = 3; i >= 1; i--) {
                var postedAt = ANALYSIS_DATE.minusDays(i).atStartOfDay();
                jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                        + "billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,created_at)"
                        + " VALUES (?,?, 'RECONCILIATION_ADJUSTMENT',?,?, 'POSTED','SYSTEM',NULL,?,UTC_TIMESTAMP(6))",
                        org, "neg-lp-" + org + "-" + i, adjustmentId, periodId, postedAt);
                var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,"
                        + "project_id,source_reconciliation_adjustment_id,created_at)"
                        + " VALUES (?,?,0,'ADJUSTMENT',-1.00000000,'CNY',?,?,UTC_TIMESTAMP(6))",
                        org, postingId, project, adjustmentId);
            }
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    /**
     * The analysis window is [analysisDate-29, analysisDate): observed offsets 29..1.
     * Six +10 CNY days (offsets 13..8) then seven -1 CNY days (offsets 7..1) — the whole
     * 14-day observed suffix nets +53 and would pass a whole-history eligibility gate, while
     * the trailing RECENT_RUN_RATE window (the last 7 buckets, net -7) extrapolates to -7 CNY
     * per remaining day. Only a window-level gate stops that forecast; this reproduces the
     * review finding at the API level.
     */
    private void insertPositiveHeadNegativeTailDailySeries() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at)"
                    + " VALUES (?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 DAY),"
                    + "DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 20 DAY),'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org);
            var periodId = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? LIMIT 1",
                    Long.class, org);
            jdbc.update("INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,"
                    + "tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,finished_at,"
                    + "created_at,updated_at)"
                    + " VALUES (?,?,'COMPLETED','fixture-v1',0,REPEAT('0',64),JSON_OBJECT(),?,UTC_TIMESTAMP(6),"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org, periodId, member);
            var runId = jdbc.queryForObject(
                    "SELECT id FROM reconciliation_run WHERE org_id=? ORDER BY id DESC LIMIT 1", Long.class, org);
            jdbc.update("INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,"
                    + "currency,case_type,external_amount,internal_amount,difference_amount,"
                    + "external_row_count,internal_row_count,status,reason_code,resolution_note,"
                    + "resolved_by_member_id,resolved_at,created_at,updated_at)"
                    + " VALUES (?,?,?,'CNY','AMOUNT_MISMATCH',5,4,-1,1,1,'RESOLVED','TEST','fixture',"
                    + "?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org, runId, accountId, member);
            var caseId = jdbc.queryForObject(
                    "SELECT id FROM reconciliation_case WHERE org_id=? ORDER BY id DESC LIMIT 1", Long.class, org);
            jdbc.update("INSERT INTO reconciliation_adjustment(org_id,reconciliation_run_id,reconciliation_case_id,"
                    + "adjustment_key,adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,"
                    + "created_by_member_id,reason_code,reason_note,created_at)"
                    + " VALUES (?,?,?,?, 'CASE_FULL',?,'CNY',-1.00000000,?,?,"
                    + "'TEST','negative net fixture',UTC_TIMESTAMP(6))",
                    org, runId, caseId, "mix-adj-" + org, accountId, periodId, member);
            var adjustmentId = jdbc.queryForObject(
                    "SELECT id FROM reconciliation_adjustment WHERE org_id=? AND adjustment_key=?",
                    Long.class, org, "mix-adj-" + org);
            // The analysis window is [analysisDate-29, analysisDate), so observed days are
            // offsets 29..1. Offsets 13..7: six +10 days. Offsets 6..1: the trailing six -1
            // days plus one more -1 at offset 7 boundary… concretely: +10 at 13..8 and -1 at
            // 7..1 gives a trailing 7-day window (7..1 plus unobserved 0 padded zero? no —
            // the window is the last 7 buckets, offsets 7..1) of net -7.
            for (var offset = 13; offset >= 8; offset--) {
                insertAdjustmentEntry(adjustmentId, periodId, offset, "10.00000000", "pos");
            }
            for (var offset = 7; offset >= 1; offset--) {
                insertAdjustmentEntry(adjustmentId, periodId, offset, "-1.00000000", "neg");
            }
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    private void insertAdjustmentEntry(long adjustmentId, long periodId, int dayOffset,
            String amount, String tag) {
        var postedAt = ANALYSIS_DATE.minusDays(dayOffset).atStartOfDay();
        jdbc.update("INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,"
                + "billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,created_at)"
                + " VALUES (?,?, 'RECONCILIATION_ADJUSTMENT',?,?, 'POSTED','SYSTEM',NULL,?,UTC_TIMESTAMP(6))",
                org, "mix-lp-" + org + "-" + tag + "-" + dayOffset, adjustmentId, periodId, postedAt);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,"
                + "project_id,source_reconciliation_adjustment_id,created_at)"
                + " VALUES (?,?,0,'ADJUSTMENT',?,'CNY',?,?,UTC_TIMESTAMP(6))",
                org, postingId, new BigDecimal(amount), project, adjustmentId);
    }

    private long openBudget(String total) {
        return openBudget(total, "0");
    }

    private long openBudget(String total, String actual) {
        var periodId = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? LIMIT 1", Long.class, org);
        jdbc.update("INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,"
                + "actual_amount,committed_amount,status,created_at,updated_at)"
                + " VALUES (?,?,'ORG',?,'CNY',?,?,0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                org, periodId, org, new BigDecimal(total), new BigDecimal(actual));
        return jdbc.queryForObject("SELECT id FROM budget WHERE org_id=?", Long.class, org);
    }

    /** The API entry point a real analysis uses; the run must complete without exception. */
    private String runAnalysisViaApi() throws Exception {
        return mockMvc.perform(post("/api/v1/cost-intelligence/history-coverage/analyze")
                        .header("Authorization", bearerFor(user)).param("currency", "CNY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void negativeShortHistoryNeverBecomesANegativeForecastAndRiskChainSurvives() throws Exception {
        var body = runAnalysisViaApi();
        assertEquals("COMPLETED", JsonPath.read(body, "$.status"));

        // The signed ledger facts stay exactly as posted: three -1 CNY entries.
        assertEquals(0, jdbc.queryForObject(
                "SELECT SUM(amount) FROM ledger_entry WHERE org_id=?", BigDecimal.class, org)
                .compareTo(new BigDecimal("-3")));

        var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=? AND scope_key=?",
                Integer.class, org, "org:" + org);
        // A reversal-dominated short history has no defensible future-usage estimate: the
        // organization forecast is absent (not zero) and risk reports it as unavailable.
        assertEquals(0, count, "a negative-net short history must not persist a forecast");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=? AND projected_amount < 0",
                Integer.class, org), "no negative projected amount may ever be persisted");

        // The budget-risk consumer over the same run must answer, not throw.
        openBudget("100.00");
        var risk = mockMvc.perform(get("/api/v1/cost-intelligence/budget-risks")
                        .header("Authorization", bearerFor(user))
                        .param("scopeType", "ORG").param("scopeId", String.valueOf(org))
                        .param("currency", "CNY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse((Boolean) JsonPath.read(risk, "$.forecastAvailable"));
        assertEquals("0.00000000", JsonPath.read(risk, "$.projectedPeriodEnd"));
    }

    @Test
    void advisorBudgetRiskEvidenceSurvivesNegativeHistory() throws Exception {
        var body = runAnalysisViaApi();
        assertEquals("COMPLETED", JsonPath.read(body, "$.status"));
        openBudget("100.00");
        var budgetId = jdbc.queryForObject("SELECT id FROM budget WHERE org_id=?", Long.class, org);

        // Advisor profile is required before an explanation can be requested.
        var logicalId = insertGlobalLogicalModel("neg-ledger-model-1");
        var wireId = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalId, "neg-ledger-wire-1");
        mockMvc.perform(put("/api/v1/ai-advisor/profile")
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"providerModelId\":" + wireId + ",\"projectId\":" + project
                                + ",\"financialScopeType\":\"PROJECT\",\"financialScopeId\":" + project
                                + ",\"budgetEnforcementMode\":\"OPTIONAL\"}"))
                .andExpect(status().isOk());

        // Evidence assembly runs inside the explanation transaction: before the fix this
        // IllegalArgumentException rolled back the whole request.
        var job = mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"subjectType\":\"BUDGET_RISK\",\"subjectId\":" + budgetId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var jobId = ((Number) JsonPath.read(job, "$.id")).longValue();
        var summary = jdbc.queryForObject(
                "SELECT CAST(summary_json AS CHAR) FROM advisor_evidence_snapshot WHERE job_id=? AND org_id=?",
                String.class, jobId, org);
        // budgetRiskSummary is stored as a bounded JSON string inside the summary object.
        var riskBlock = (String) JsonPath.read(summary, "$.budgetRiskSummary");
        assertEquals(Boolean.FALSE, JsonPath.read(riskBlock, "$.forecastAvailable"));
    }

    /**
     * A negative budget actual is a legitimate signed ledger net (credits/reversals may drive
     * it below zero by design). The risk API must net it into exposure and answer instead of
     * rejecting the component, with or without a forecast.
     */
    @Test
    void riskApiAnswersOnLegitimatelyNegativeBudgetActual() throws Exception {
        var body = runAnalysisViaApi();
        assertEquals("COMPLETED", JsonPath.read(body, "$.status"));
        openBudget("100.00", "-1.00");
        var budgetId = jdbc.queryForObject("SELECT id FROM budget WHERE org_id=?", Long.class, org);

        var risk = mockMvc.perform(get("/api/v1/cost-intelligence/budget-risks")
                        .header("Authorization", bearerFor(user))
                        .param("scopeType", "ORG").param("scopeId", String.valueOf(org))
                        .param("currency", "CNY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // immediate = actual + committed + reservations = -1.00 + 0 + 0
        assertEquals("-1.00000000", JsonPath.read(risk, "$.immediateExposure"));
        assertEquals("-1.00000000", JsonPath.read(risk, "$.projectedPeriodEnd"));
        assertEquals("LOW", JsonPath.read(risk, "$.risk"));
        assertFalse((Boolean) JsonPath.read(risk, "$.forecastAvailable"));

        // The same negative actual must flow through the Advisor evidence path unchanged.
        var logicalId = insertGlobalLogicalModel("neg-ledger-model-2");
        var wireId = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalId, "neg-ledger-wire-2");
        mockMvc.perform(put("/api/v1/ai-advisor/profile")
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"providerModelId\":" + wireId + ",\"projectId\":" + project
                                + ",\"financialScopeType\":\"PROJECT\",\"financialScopeId\":" + project
                                + ",\"budgetEnforcementMode\":\"OPTIONAL\"}"))
                .andExpect(status().isOk());
        var job = mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"subjectType\":\"BUDGET_RISK\",\"subjectId\":" + budgetId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var jobId = ((Number) JsonPath.read(job, "$.id")).longValue();
        var summary = jdbc.queryForObject(
                "SELECT CAST(summary_json AS CHAR) FROM advisor_evidence_snapshot WHERE job_id=? AND org_id=?",
                String.class, jobId, org);
        var riskBlock = (String) JsonPath.read(summary, "$.budgetRiskSummary");
        assertEquals("-1.00000000", JsonPath.read(riskBlock, "$.immediate"));
        assertEquals(Boolean.FALSE, JsonPath.read(riskBlock, "$.forecastAvailable"));
    }

    /**
     * A positive whole-suffix net can hide a reversal-dominated trailing window: six +10 days
     * followed by seven -1 days nets +53 overall while the trailing 7-day window nets -7. The
     * window-level eligibility gate must withhold the forecast instead of persisting a negative
     * extrapolation, and the risk chain must stay available throughout.
     */
    @Test
    void positiveWholeHistoryWithNegativeTailStillWithholdsForecast() throws Exception {
        insertPositiveHeadNegativeTailDailySeries();
        var body = runAnalysisViaApi();
        assertEquals("COMPLETED", JsonPath.read(body, "$.status"));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=? AND projected_amount < 0",
                Integer.class, org), "no negative projected amount may ever be persisted");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=? AND scope_key=?",
                Integer.class, org, "org:" + org),
                "a trailing-window reversal-dominated history must not persist a forecast");

        openBudget("100.00");
        var risk = mockMvc.perform(get("/api/v1/cost-intelligence/budget-risks")
                        .header("Authorization", bearerFor(user))
                        .param("scopeType", "ORG").param("scopeId", String.valueOf(org))
                        .param("currency", "CNY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse((Boolean) JsonPath.read(risk, "$.forecastAvailable"));
    }
}
