package com.aicostops.intelligence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.aicostops.providerhub.application.ControlPlaneFixtureSupport;

/**
 * Scoped budget-risk isolation over real MySQL (M18 round-3 P1): TEAM/COST_CENTER budgets project
 * from their own forecast grains — a missing scoped forecast means zero future usage, never the
 * whole-organization forecast.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET })
@AutoConfigureMockMvc
@Tag("integration")
class ScopedBudgetRiskIntegrationTest extends ControlPlaneFixtureSupport {

    @Autowired
    private MockMvc mockMvc;

    private long organizationId;
    private long readerUserId;
    private long readerMemberId;
    private long teamA;
    private long teamB;
    private long costCenterA;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Risk Org", "risk-org");
        readerUserId = insertUser("risk-reader@example.com");
        readerMemberId = insertMember(organizationId, readerUserId);
        teamA = insertTeam(organizationId, "team-a");
        teamB = insertTeam(organizationId, "team-b");
        costCenterA = insertCostCenter(organizationId, "cc-a");
        insertBudget("TEAM", teamA, "100.00", "80.00", "5.00");
        insertBudget("TEAM", teamB, "1000.00", "10.00", "0.00");
        insertBudget("COST_CENTER", costCenterA, "50.00", "20.00", "5.00");
        var yesterday = LocalDate.now().minusDays(1);
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,"
                + "created_at) VALUES (?,'" + yesterday + "','USD',1,'COMPLETED',UTC_TIMESTAMP(6))",
                organizationId);
        var runId = jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=?", Long.class,
                organizationId);
        insertForecast(runId, "TEAM", "team:" + teamA, "10.00");
        insertForecast(runId, "ORGANIZATION", "org:" + organizationId, "9000.00");
        createPermissionRole("RISK_READER", List.of("BUDGET_READ"));
        assign(readerMemberId, "RISK_READER", "ORG", organizationId);
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void teamBudgetUsesOwnForecastGrain() throws Exception {
        var body = riskBody("TEAM", teamA);
        assertMoney("85.00", body, "$.immediateExposure");
        assertMoney("95.00", body, "$.projectedPeriodEnd");
        assertRisk("HIGH", body);
    }

    @Test
    void teamWithoutForecastNeverReceivesOrgForecast() throws Exception {
        var body = riskBody("TEAM", teamB);
        assertMoney("10.00", body, "$.immediateExposure");
        assertMoney("10.00", body, "$.projectedPeriodEnd");
        assertRisk("LOW", body);
    }

    @Test
    void costCenterWithoutForecastProjectsActualOnly() throws Exception {
        var body = riskBody("COST_CENTER", costCenterA);
        assertMoney("25.00", body, "$.immediateExposure");
        assertMoney("25.00", body, "$.projectedPeriodEnd");
        assertRisk("LOW", body);
    }

    private String riskBody(String scopeType, long scopeId) throws Exception {
        return mockMvc.perform(get("/api/v1/cost-intelligence/budget-risks")
                        .header("Authorization", bearerFor(readerUserId))
                        .param("scopeType", scopeType).param("scopeId", String.valueOf(scopeId))
                        .param("currency", "USD"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void assertMoney(String expected, String body, String path) {
        var actual = new java.math.BigDecimal(com.jayway.jsonpath.JsonPath.<String>read(body, path));
        org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " at " + path);
    }

    private void assertRisk(String expected, String body) {
        org.junit.jupiter.api.Assertions.assertEquals(expected,
                com.jayway.jsonpath.JsonPath.read(body, "$.risk"));
    }

    private void insertBudget(String scopeType, long scopeId, String total, String actual, String committed) {
        var periods = jdbc.query("SELECT id FROM billing_period WHERE org_id=? LIMIT 1",
                (rs, i) -> rs.getLong(1), organizationId);
        Long periodId = periods.isEmpty() ? null : periods.get(0);
        if (periodId == null) {
            jdbc.update("INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at)"
                    + " VALUES (?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY),'OPEN',"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", organizationId);
            periodId = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? LIMIT 1", Long.class,
                    organizationId);
        }
        jdbc.update("INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,"
                + "actual_amount,committed_amount,status,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                organizationId, periodId, scopeType, scopeId, "USD", new java.math.BigDecimal(total),
                new java.math.BigDecimal(actual), new java.math.BigDecimal(committed));
    }

    private void insertForecast(long runId, String scopeType, String scopeKey, String projected) {
        jdbc.update("INSERT INTO cost_forecast_snapshot(org_id,run_id,scope_type,scope_key,currency,"
                + "projected_amount,method,history_bucket_count,confidence,observed_through,created_at)"
                + " VALUES (?,?,?,?,'USD',?,'DAMPED_HOLT',20,'HIGH',CURDATE(),UTC_TIMESTAMP(6))",
                organizationId, runId, scopeType, scopeKey, new java.math.BigDecimal(projected));
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM cost_forecast_snapshot");
        jdbc.update("DELETE FROM cost_intelligence_run");
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM billing_period");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code='RISK_READER'");
        jdbc.update("DELETE FROM `role` WHERE code='RISK_READER'");
    }
}
