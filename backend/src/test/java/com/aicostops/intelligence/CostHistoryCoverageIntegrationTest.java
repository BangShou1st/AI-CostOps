package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aicostops.audit.application.AuditService;
import com.aicostops.intelligence.application.CostHistoryCoverageCommandService;
import com.aicostops.intelligence.application.CostHistoryCoverageCommandService.ReviewRequest;
import com.aicostops.intelligence.domain.CostHistoryCoverage.*;
import com.aicostops.providerhub.application.ControlPlaneFixtureSupport;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET})
@AutoConfigureMockMvc
@Tag("integration")
class CostHistoryCoverageIntegrationTest extends ControlPlaneFixtureSupport {
    private static final String ROOT = "/api/v1/cost-intelligence/history-coverage";
    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired CostHistoryCoverageCommandService commands;
    @Autowired com.aicostops.intelligence.application.CostAnalysisInputService inputs;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoSpyBean AuditService audit;
    private long org, user, member, project;
    private String role;
    private LocalDate day;

    @BeforeEach void setup() {
        var suffix = UUID.randomUUID().toString();
        org = insertOrganization("Coverage QA", "coverage-" + suffix);
        user = insertUser("coverage-" + suffix + "@example.test");
        member = insertMember(org, user);
        project = insertProject(org, "coverage-project");
        role = "COV_" + suffix.substring(0, 8);
        createPermissionRole(role, List.of("COST_READ", "COST_COVERAGE_MANAGE"));
        assign(member, role, "ORG", org);
        day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
        flushRedis();
    }

    @AfterEach void cleanup() {
        reset(audit);
        jdbc.update("DELETE FROM savings_recommendation WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_forecast_snapshot WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_anomaly WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_intelligence_run WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_history_coverage WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", org);
        jdbc.update("DELETE FROM audit_event WHERE org_id=?", org);
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", member);
        jdbc.update("DELETE FROM role_permission WHERE role_id IN (SELECT id FROM `role` WHERE code=?)", role);
        jdbc.update("DELETE FROM `role` WHERE code=?", role);
        jdbc.update("DELETE FROM project WHERE org_id=?", org);
        jdbc.update("DELETE FROM organization_member WHERE id=?", member);
        jdbc.update("DELETE FROM organization WHERE id=?", org);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
        flushRedis();
    }

    private ReviewRequest request(long revision, Status status) {
        return new ReviewRequest("CNY", Scope.PROJECT, project, day, status, "All expected posting sources reviewed", revision);
    }
    private String json(long revision, String status) {
        return "{\"currency\":\"CNY\",\"scope\":\"PROJECT\",\"scopeId\":" + project
                + ",\"postingDay\":\"" + day + "\",\"status\":\"" + status
                + "\",\"reviewNote\":\"All expected posting sources reviewed\",\"expectedRevision\":" + revision + "}";
    }

    @Test void reviewAndRevocationChangeUnknownDayWithoutInventingLedgerMoney() throws Exception {
        mvc.perform(get(ROOT).header("Authorization", bearerFor(user)).param("currency", "CNY")
                .param("scope", "PROJECT").param("scopeId", Long.toString(project))
                .param("start", day.toString()).param("end", day.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.days[0].observation").value("UNKNOWN"))
                .andExpect(jsonPath("$.days[0].amount").doesNotExist());
        mvc.perform(put(ROOT).header("Authorization", bearerFor(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json(0, "REVIEWED_COMPLETE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get(ROOT).header("Authorization", bearerFor(user)).param("currency", "CNY")
                .param("scope", "PROJECT").param("scopeId", Long.toString(project))
                .param("start", day.toString()).param("end", day.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days[0].observation").value("REVIEWED_ZERO"))
                .andExpect(jsonPath("$.days[0].amount").value("0"))
                .andExpect(jsonPath("$.days[0].reviewValid").value(true));
        commands.review(new AuthenticatedUser(user, 7), request(1, Status.REVOKED));
        mvc.perform(get(ROOT).header("Authorization", bearerFor(user)).param("currency", "CNY")
                .param("scope", "PROJECT").param("scopeId", Long.toString(project))
                .param("start", day.toString()).param("end", day.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days[0].observation").value("UNKNOWN"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=? AND event_type='COST_HISTORY_COVERAGE_REVIEWED'", Integer.class, org));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Integer.class, org));
    }

    @Test void staleRevisionAndForeignScopeCannotOverwriteReview() throws Exception {
        commands.review(new AuthenticatedUser(user, 7), request(0, Status.REVIEWED_COMPLETE));
        mvc.perform(put(ROOT).header("Authorization", bearerFor(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json(0, "REVOKED"))).andExpect(status().isConflict());
        mvc.perform(put(ROOT).header("Authorization", bearerFor(user)).contentType(MediaType.APPLICATION_JSON)
                .content(json(1, "REVOKED").replace("\"scopeId\":" + project, "\"scopeId\":9223372036854775807")))
                .andExpect(status().isNotFound());
        assertEquals("REVIEWED_COMPLETE", jdbc.queryForObject("SELECT status FROM cost_history_coverage WHERE org_id=?", String.class, org));
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM cost_history_coverage_revision WHERE org_id=? AND currency='CNY'", Long.class, org));
    }

    @Test void auditFailureRollsBackDeclarationAndInputRevision() {
        doThrow(new IllegalStateException("synthetic audit failure")).when(audit)
                .append(eq("COST_HISTORY_COVERAGE_REVIEWED"), anyLong(), anyLong(), anyString(), anyLong(), anyMap());
        assertThrows(IllegalStateException.class,
                () -> commands.review(new AuthenticatedUser(user, 7), request(0, Status.REVIEWED_COMPLETE)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cost_history_coverage WHERE org_id=?", Integer.class, org));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cost_history_coverage_revision WHERE org_id=?", Integer.class, org));
    }

    @Test void scopeNamesAndIdsAreRestrictedToTheCurrentOrganization() throws Exception {
        var foreignOrg = insertOrganization("Foreign coverage", "foreign-coverage-" + UUID.randomUUID());
        var foreignProject = insertProject(foreignOrg, "coverage-project");
        try {
            mvc.perform(get(ROOT + "/scopes").header("Authorization", bearerFor(user)).param("search", "coverage-project"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].scopeId").value(Long.toString(project)))
                    .andExpect(jsonPath("$[0].scope").value("PROJECT"));
            mvc.perform(get(ROOT).header("Authorization", bearerFor(user)).param("currency", "CNY")
                    .param("scope", "PROJECT").param("scopeId", Long.toString(foreignProject))
                    .param("start", day.toString()).param("end", day.plusDays(1).toString()))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("DELETE FROM project WHERE org_id=?", foreignOrg);
            jdbc.update("DELETE FROM organization WHERE id=?", foreignOrg);
        }
    }

    @Test void reviewedZeroScopeProducesForecastAndRevocationMakesOldSnapshotStale() throws Exception {
        for (int i = 1; i <= 3; i++) {
            commands.review(new AuthenticatedUser(user, 7), new ReviewRequest("CNY", Scope.PROJECT,
                    project, day.minusDays(i), Status.REVIEWED_COMPLETE, "All expected posting sources reviewed", (long) i - 1));
        }
        var first = commands.refreshAnalysis(new AuthenticatedUser(user, 7), "CNY");
        assertEquals("COMPLETED", first.status());
        assertEquals(first, commands.refreshAnalysis(new AuthenticatedUser(user, 7), "CNY"));
        mvc.perform(get("/api/v1/cost-intelligence/forecasts").header("Authorization", bearerFor(user)).param("currency", "CNY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scopeKey").value("project:" + project))
                .andExpect(jsonPath("$[0].historyBucketCount").value(3))
                .andExpect(jsonPath("$[0].confidence").value("LOW"))
                .andExpect(jsonPath("$[0].projectedAmount").value("0.00000000"));
        commands.review(new AuthenticatedUser(user, 7), new ReviewRequest("CNY", Scope.PROJECT,
                project, day.minusDays(1), Status.REVOKED, "Completeness could no longer be established", 3L));
        mvc.perform(get("/api/v1/cost-intelligence/summary").header("Authorization", bearerFor(user)).param("currency", "CNY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STALE"));
        mvc.perform(get("/api/v1/cost-intelligence/forecasts").header("Authorization", bearerFor(user)).param("currency", "CNY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        var second = commands.refreshAnalysis(new AuthenticatedUser(user, 7), "CNY");
        assertNotEquals(first.runId(), second.runId());
        assertEquals("COMPLETED", second.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=? AND run_id=?", Integer.class, org, first.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=? AND run_id=?", Integer.class, org, second.runId()));
    }

    @Test void oldTransactionReadViewCannotBeCombinedWithANewerReviewRevision() {
        commands.review(new AuthenticatedUser(user, 7), request(0, Status.REVIEWED_COMPLETE));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertEquals(1L, jdbc.queryForObject("SELECT revision FROM cost_history_coverage_revision WHERE org_id=? AND currency='CNY'", Long.class, org));
                try {
                    assertEquals(2L, executor.submit(() -> commands.review(new AuthenticatedUser(user, 7), request(1, Status.REVOKED)))
                            .get(15, java.util.concurrent.TimeUnit.SECONDS).revision());
                } catch (Exception failure) {
                    throw new IllegalStateException("Concurrent review did not finish", failure);
                }
                var failure = assertThrows(com.aicostops.shared.web.DomainException.class,
                        () -> inputs.lockAndCapture(org, "CNY", day,
                                com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION));
                assertEquals(com.aicostops.shared.web.ProblemCode.STATE_CONFLICT, failure.code());
                tx.setRollbackOnly();
            });
        }
    }
}
