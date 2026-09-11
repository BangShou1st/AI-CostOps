package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
/** P1-1 atomic terminal convergence over real MySQL. */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Tag("integration")
class AdvisorTerminalConvergenceIntegrationTest extends ControlPlaneFixtureSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private AdvisorService advisorService;
    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long projectA;
    private long projectB;
    private long teamT;
    private long modelA;
    private long modelB;
    private long budgetA;
    @BeforeEach void setUp() {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Terminal Org", "terminal-org");
        // RED guard: force app_user.id != organization_member.id deterministically,
        // independent of auto-increment counters and test execution order. Probe the
        // current offset, then shift the app_user sequence asymmetrically when aligned.
        var probeUser = insertUser("terminal-probe-" + System.nanoTime() + "@example.com");
        var probeMember = insertMember(organizationId, probeUser);
        if (probeUser == probeMember) {
            insertUser("terminal-offset-" + System.nanoTime() + "@example.com");
        }
        managerUserId = insertUser("terminal-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        assertNotEquals(managerUserId, managerMemberId,
                "Advisor fixture must keep app_user.id != organization_member.id so member/user confusion fails fast");
        projectA = insertProject(organizationId, "proj-a");
        projectB = insertProject(organizationId, "proj-b");
        teamT = insertTeam(organizationId, "team-t");
        var logicalA = insertGlobalLogicalModel("term-model-a");
        var logicalB = insertGlobalLogicalModel("term-model-b");
        modelA = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalA, "term-model-a");
        modelB = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalB, "term-model-b");
        budgetA = insertBudget(organizationId, "PROJECT", projectA, "USD", "100.00");
        createPermissionRole("TERM_MANAGER", List.of("AI_ADVISOR_MANAGE", "AI_ADVISOR_USE"));
        assign(managerMemberId, "TERM_MANAGER", "ORG", organizationId);
        flushRedis();
    }
    @AfterEach void tearDown() { cleanDatabase(); }
    @Test void supersedeTerminalizesJobAndAttemptTogether() throws Exception {
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var job1 = requestExplanation("BUDGET_RISK", budgetA);
        assertEquals("PENDING", jobStatus(job1));
        assertEquals("PENDING", attemptStatus(job1, 1));
        putProfile(modelB, projectB, "TEAM", teamT, "REQUIRED");
        assertEquals("FAILED", jobStatus(job1));
        assertEquals("PROFILE_SUPERSEDED", jobFailureCode(job1));
        assertEquals("FAILED", attemptStatus(job1, 1));
        assertEquals("PROFILE_SUPERSEDED", attemptFailureCode(job1, 1));
    }
    @Test void preDispatchEvidenceFailureTerminalizesBoth() throws Exception {
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var anomaly = insertAnomaly(organizationId, "80.00", "50.00", "30.00");
        var jobId = requestExplanation("ANOMALY", anomaly);
        jdbc.update("UPDATE advisor_evidence_snapshot SET evidence_fingerprint=? WHERE job_id=? AND org_id=?", "0".repeat(64), jobId, organizationId);
        var claimed = advisorService.claimNext("w-evidence");
        assertNotNull(claimed);
        advisorService.fail(claimed.jobId(), claimed.organizationId(), claimed.token(), "EVIDENCE_INTEGRITY_FAILED");
        assertEquals("FAILED", jobStatus(jobId));
        assertEquals("FAILED", attemptStatus(jobId, 1));
    }
    @Test void completionRollbackWhenAttemptFails() throws Exception {
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var anomaly = insertAnomaly(organizationId, "80.00", "50.00", "30.00");
        var jobId = requestExplanation("ANOMALY", anomaly);
        var claimed = advisorService.claimNext("w-rc");
        assertNotNull(claimed);
        jdbc.update("UPDATE advisor_inference_attempt SET status=? WHERE job_id=? AND org_id=? AND attempt_no=1", "COMPLETED", jobId, organizationId);
        var refs = advisorService.knownFactReferences(jobId, organizationId);
        var ref = refs.iterator().next();
        var narrative = "{\"summary\":\"Spend rose.\",\"driversExplanation\":\"X.\",\"recommendedActions\":[],\"warnings\":[],\"factReferences\":[\"" + ref + "\"]}";
        assertThrows(Exception.class, () -> advisorService.complete(jobId, organizationId, claimed.token(), narrative, refs));
        assertNotEquals("COMPLETED", jobStatus(jobId));
    }
    @Test void failureRollbackWhenAttemptFails() throws Exception {
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var anomaly = insertAnomaly(organizationId, "81.00", "50.00", "31.00");
        var jobId = requestExplanation("ANOMALY", anomaly);
        var claimed = advisorService.claimNext("w-rf");
        assertNotNull(claimed);
        jdbc.update("UPDATE advisor_inference_attempt SET status=? WHERE job_id=? AND org_id=? AND attempt_no=1", "COMPLETED", jobId, organizationId);
        assertThrows(Exception.class, () -> advisorService.fail(jobId, organizationId, claimed.token(), "PROVIDER_UNAVAILABLE"));
        assertNotEquals("FAILED", jobStatus(jobId));
    }
    @Test void successTerminalizesBoth() throws Exception {
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var anomaly = insertAnomaly(organizationId, "82.00", "50.00", "32.00");
        var jobId = requestExplanation("ANOMALY", anomaly);
        var claimed = advisorService.claimNext("w-ok");
        assertNotNull(claimed);
        var refs = advisorService.knownFactReferences(jobId, organizationId);
        var ref = refs.iterator().next();
        var narrative = "{\"summary\":\"Spend rose.\",\"driversExplanation\":\"X.\",\"recommendedActions\":[],\"warnings\":[],\"factReferences\":[\"" + ref + "\"]}";
        advisorService.complete(jobId, organizationId, claimed.token(), narrative, refs);
        assertEquals("COMPLETED", jobStatus(jobId));
        assertEquals("COMPLETED", attemptStatus(jobId, 1));
    }
    @Test void retryKeepsG1ImmutableWithDistinctG2() throws Exception {
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var anomaly = insertAnomaly(organizationId, "83.00", "50.00", "33.00");
        var jobId = requestExplanation("ANOMALY", anomaly);
        var first = advisorService.claimNext("w-r1");
        // P1-1 G1/G2 lineage via attempt rows (no FK on attempt.gateway_request_id).
        // Job-level gateway link is covered by Gateway atomic-link unit tests; here we prove
        // retry immutability and distinct lineage without requiring gateway_request parents.
        jdbc.update("UPDATE advisor_inference_attempt SET gateway_request_id=? WHERE job_id=? AND org_id=? AND attempt_no=1", 910001L, jobId, organizationId);
        advisorService.fail(jobId, organizationId, first.token(), "PROVIDER_UNAVAILABLE");
        var g1 = attemptGateway(jobId, 1);
        assertEquals(Long.valueOf(910001L), g1);
        mockMvc.perform(post("/api/v1/ai-advisor/explanations/{id}/retry", jobId).header("Authorization", bearerFor(managerUserId))).andExpect(status().isOk());
        assertEquals("PENDING", jobStatus(jobId));
        assertEquals("FAILED", attemptStatus(jobId, 1));
        assertEquals(g1, attemptGateway(jobId, 1));
        var second = advisorService.claimNext("w-r2");
        jdbc.update("UPDATE advisor_inference_attempt SET gateway_request_id=? WHERE job_id=? AND org_id=? AND attempt_no=2", 910002L, jobId, organizationId);
        var refs = advisorService.knownFactReferences(jobId, organizationId);
        var ref = refs.iterator().next();
        var narrative = "{\"summary\":\"Again.\",\"driversExplanation\":\"X.\",\"recommendedActions\":[],\"warnings\":[],\"factReferences\":[\"" + ref + "\"]}";
        advisorService.complete(jobId, organizationId, second.token(), narrative, refs);
        assertEquals("COMPLETED", jobStatus(jobId));
        assertEquals("FAILED", attemptStatus(jobId, 1));
        assertEquals("COMPLETED", attemptStatus(jobId, 2));
        assertNotEquals(g1, attemptGateway(jobId, 2));
    }
    @Test void requesterIdentityPreservedAcrossCompletionAndRetry() throws Exception {
        // Regression for PR #156 Hosted CI: advisor_inference_job.requested_by must be
        // app_user.id (human requester), never organization_member.id, because
        // audit_event.actor_user_id FKs app_user(id). The fixture above guarantees
        // app_user.id != organization_member.id so any cross-type reuse fails fast.
        assertNotEquals(managerUserId, managerMemberId,
                "Fixture must force app_user.id != organization_member.id");
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var anomaly = insertAnomaly(organizationId, "84.00", "50.00", "34.00");
        var jobId = requestExplanation("ANOMALY", anomaly);
        assertEquals(Long.valueOf(managerUserId), jobRequestedBy(jobId),
                "advisor_inference_job.requested_by must store app_user.id");
        assertEquals(Long.valueOf(managerUserId), latestAuditActor("AI_ADVISOR_EXPLANATION_REQUESTED", jobId),
                "REQUESTED audit actor must be the human app_user.id");
        var claimed = advisorService.claimNext("w-identity");
        assertNotNull(claimed);
        var refs = advisorService.knownFactReferences(jobId, organizationId);
        var ref = refs.iterator().next();
        var narrative = "{\"summary\":\"Spend rose.\",\"driversExplanation\":\"X.\",\"recommendedActions\":[],\"warnings\":[],\"factReferences\":[\"" + ref + "\"]}";
        advisorService.complete(jobId, organizationId, claimed.token(), narrative, refs);
        assertEquals("COMPLETED", jobStatus(jobId));
        assertEquals("COMPLETED", attemptStatus(jobId, 1));
        assertEquals(Long.valueOf(managerUserId), latestAuditActor("AI_ADVISOR_EXPLANATION_COMPLETED", jobId),
                "COMPLETED audit actor must remain the human app_user.id, not organization_member.id");
        // Explicit retry must preserve the original human requester lineage.
        mockMvc.perform(post("/api/v1/ai-advisor/explanations/{id}/retry", jobId).header("Authorization", bearerFor(managerUserId))).andExpect(status().isOk());
        assertEquals(Long.valueOf(managerUserId), jobRequestedBy(jobId),
                "Retry must not rewrite requested_by to organization_member.id");
        var second = advisorService.claimNext("w-identity-r2");
        assertNotNull(second);
        var refs2 = advisorService.knownFactReferences(jobId, organizationId);
        var ref2 = refs2.iterator().next();
        var narrative2 = "{\"summary\":\"Again.\",\"driversExplanation\":\"X.\",\"recommendedActions\":[],\"warnings\":[],\"factReferences\":[\"" + ref2 + "\"]}";
        advisorService.complete(jobId, organizationId, second.token(), narrative2, refs2);
        assertEquals("COMPLETED", jobStatus(jobId));
        // First execution already COMPLETED; retry appends attempt 2 while attempt 1 stays immutable.
        assertEquals("COMPLETED", attemptStatus(jobId, 1));
        assertEquals("COMPLETED", attemptStatus(jobId, 2));
        var completedActors = jdbc.query("SELECT actor_user_id FROM audit_event WHERE org_id=? AND event_type='AI_ADVISOR_EXPLANATION_COMPLETED' AND subject_type='ADVISOR_JOB' AND subject_id=? ORDER BY id",
                (rs, i) -> rs.getLong(1), organizationId, jobId);
        assertEquals(2, completedActors.size(), "Expected two COMPLETED audits (initial + retry)");
        for (var actor : completedActors) {
            assertEquals(Long.valueOf(managerUserId), actor,
                    "Every COMPLETED audit actor must be the human app_user.id");
        }
    }
    private long putProfile(long pm, long pj, String st, long sid, String bm) throws Exception {
        var body = mockMvc.perform(put("/api/v1/ai-advisor/profile").header("Authorization", bearerFor(managerUserId)).contentType("application/json").content("{\"providerModelId\":" + pm + ",\"projectId\":" + pj + ",\"financialScopeType\":\"" + st + "\",\"financialScopeId\":" + sid + ",\"budgetEnforcementMode\":\"" + bm + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
    private long requestExplanation(String t, long sid) throws Exception {
        var body = mockMvc.perform(post("/api/v1/ai-advisor/explanations").header("Authorization", bearerFor(managerUserId)).contentType("application/json").content("{\"subjectType\":\"" + t + "\",\"subjectId\":" + sid + "}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
    private String jobStatus(long j) throws Exception {
        var b = mockMvc.perform(get("/api/v1/ai-advisor/explanations/{id}", j).header("Authorization", bearerFor(managerUserId))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(b, "$.status");
    }
    private String jobFailureCode(long j) throws Exception {
        var b = mockMvc.perform(get("/api/v1/ai-advisor/explanations/{id}", j).header("Authorization", bearerFor(managerUserId))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(b, "$.failureCode");
    }
    private String attemptStatus(long j, int n) {
        return jdbc.queryForObject("SELECT status FROM advisor_inference_attempt WHERE job_id=? AND org_id=? AND attempt_no=?", String.class, j, organizationId, n);
    }
    private String attemptFailureCode(long j, int n) {
        return jdbc.queryForObject("SELECT failure_code FROM advisor_inference_attempt WHERE job_id=? AND org_id=? AND attempt_no=?", String.class, j, organizationId, n);
    }
    private Long attemptGateway(long j, int n) {
        return jdbc.queryForObject("SELECT gateway_request_id FROM advisor_inference_attempt WHERE job_id=? AND org_id=? AND attempt_no=?", Long.class, j, organizationId, n);
    }
    private Long jobRequestedBy(long j) {
        return jdbc.queryForObject("SELECT requested_by FROM advisor_inference_job WHERE id=? AND org_id=?", Long.class, j, organizationId);
    }
    private Long latestAuditActor(String eventType, long j) {
        return jdbc.queryForObject("SELECT actor_user_id FROM audit_event WHERE org_id=? AND event_type=? AND subject_type='ADVISOR_JOB' AND subject_id=? ORDER BY id DESC LIMIT 1",
                Long.class, organizationId, eventType, j);
    }
    private long insertAnomaly(long org, String o, String b, String d) {
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,created_at) VALUES (?,CURDATE(),'USD',1,'COMPLETED',UTC_TIMESTAMP(6))", org);
        var run = jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=?", Long.class, org);
        jdbc.update("INSERT INTO cost_anomaly(org_id,run_id,grain_type,grain_key,currency,observed_amount,baseline_amount,delta_amount,delta_percent,robust_z_score,drivers_json,created_at) VALUES (?,?,'ORGANIZATION',?,'USD',?,?,?,0,3.5,CAST(\'[]\' AS JSON),UTC_TIMESTAMP(6))", org, run, "org:" + org, new java.math.BigDecimal(o), new java.math.BigDecimal(b), new java.math.BigDecimal(d));
        return jdbc.queryForObject("SELECT id FROM cost_anomaly WHERE org_id=? AND run_id=?", Long.class, org, run);
    }
    private long insertBudget(long org, String st, long sid, String cur, String total) {
        var ps = jdbc.query("SELECT id FROM billing_period WHERE org_id=? LIMIT 1", (rs, i) -> rs.getLong(1), org);
        Long pid = ps.isEmpty() ? null : ps.get(0);
        if (pid == null) {
            jdbc.update("INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at) VALUES (?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY),'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org);
            pid = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? LIMIT 1", Long.class, org);
        }
        jdbc.update("INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,actual_amount,committed_amount,status,created_at,updated_at) VALUES (?,?,?,?,?,?,0,0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", org, pid, st, sid, cur, new java.math.BigDecimal(total));
        return jdbc.queryForObject("SELECT id FROM budget WHERE org_id=? AND scope_type=? AND scope_id=?", Long.class, org, st, sid);
    }
    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM advisor_evidence_snapshot");
        jdbc.update("DELETE FROM advisor_explanation");
        jdbc.update("DELETE FROM advisor_inference_attempt");
        jdbc.update("DELETE FROM advisor_inference_job");
        jdbc.update("DELETE FROM gateway_credential_model");
        jdbc.update("UPDATE gateway_credential SET predecessor_credential_id=NULL, advisor_profile_id=NULL");
        jdbc.update("DELETE FROM gateway_credential");
        jdbc.update("DELETE FROM advisor_profile");
        jdbc.update("DELETE FROM service_identity");
        jdbc.update("DELETE FROM cost_anomaly");
        jdbc.update("DELETE FROM cost_intelligence_run");
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM billing_period");
        jdbc.update("DELETE FROM provider_model WHERE provider_model_name LIKE 'term-model-%'");
        jdbc.update("DELETE FROM model_catalog WHERE model_key LIKE 'term-model-%'");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code='TERM_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='TERM_MANAGER'");
    }
}
