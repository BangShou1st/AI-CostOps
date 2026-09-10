package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * Server-generated immutable evidence over real MySQL (M18 round-3 P1): the snapshot persists
 * exactly the frozen facts/drivers the model will receive, its fingerprint matches the job
 * fingerprint, client-supplied money is never trusted, and foreign/missing subjects are rejected.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Tag("integration")
class AdvisorEvidenceSnapshotIntegrationTest extends ControlPlaneFixtureSupport {

    @Autowired
    private MockMvc mockMvc;

    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long projectA;
    private long modelA;
    private long anomalyId;

    @BeforeEach
    void setUp() throws Exception {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Evidence Org", "evidence-org");
        managerUserId = insertUser("evidence-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        projectA = insertProject(organizationId, "proj-a");
        var logicalA = insertGlobalLogicalModel("ev-model-a");
        modelA = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalA, "ev-model-a");
        createPermissionRole("EV_MANAGER", List.of("AI_ADVISOR_MANAGE", "AI_ADVISOR_USE"));
        assign(managerMemberId, "EV_MANAGER", "ORG", organizationId);
        flushRedis();
        putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        anomalyId = insertAnomaly(organizationId, "80.00", "50.00", "30.00");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void snapshotPersistsFrozenFactsAndMatchingFingerprint() throws Exception {
        var jobId = requestExplanation("{\"subjectType\":\"ANOMALY\",\"subjectId\":" + anomalyId + "}");
        var snapshot = jdbc.queryForMap("SELECT facts_json,drivers_json,summary_json,evidence_fingerprint,"
                + "currency,subject_type,subject_id FROM advisor_evidence_snapshot"
                + " WHERE job_id=? AND org_id=?", jobId, organizationId);
        var facts = snapshot.get("facts_json").toString();
        assertTrue(facts.contains("anomaly:" + anomalyId + ":observed"));
        assertTrue(facts.contains("80.00"));
        var drivers = snapshot.get("drivers_json").toString();
        assertTrue(drivers.contains("PROVIDER"));
        assertTrue(drivers.contains("12.50"));
        var jobFingerprint = jdbc.queryForObject("SELECT evidence_fingerprint FROM advisor_inference_job"
                + " WHERE id=? AND org_id=?", String.class, jobId, organizationId);
        assertEquals(jobFingerprint, snapshot.get("evidence_fingerprint").toString());
        var refs = jdbc.queryForObject("SELECT CAST(evidence_refs_json AS CHAR) FROM advisor_inference_job"
                + " WHERE id=? AND org_id=?", String.class, jobId, organizationId);
        assertTrue(refs.contains("anomaly:" + anomalyId + ":driver:0"));
    }

    @Test
    void clientSuppliedMoneyIsNeverTrusted() throws Exception {
        var jobId = requestExplanation("{\"subjectType\":\"ANOMALY\",\"subjectId\":" + anomalyId
                + ",\"facts\":[{\"factId\":\"evil\",\"amount\":\"999999.00\"}],"
                + "\"forecastSummary\":\"rich!\"}");
        var facts = jdbc.queryForObject("SELECT CAST(facts_json AS CHAR) FROM advisor_evidence_snapshot"
                + " WHERE job_id=? AND org_id=?", String.class, jobId, organizationId);
        assertFalse(facts.contains("999999"));
        assertFalse(facts.contains("evil"));
        assertTrue(facts.contains("80.00"));
    }

    @Test
    void foreignAndMissingSubjectsAreRejected() throws Exception {
        var foreignOrg = insertOrganization("Foreign Org", "evidence-foreign");
        var foreignAnomaly = insertAnomaly(foreignOrg, "10.00", "5.00", "5.00");
        mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"subjectType\":\"ANOMALY\",\"subjectId\":" + foreignAnomaly + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"subjectType\":\"ANOMALY\",\"subjectId\":999998001}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void explicitRetryClearsStaleLineageAndCreatesFreshAttempt() throws Exception {
        var jobId = requestExplanation("{\"subjectType\":\"ANOMALY\",\"subjectId\":" + anomalyId + "}");
        jdbc.update("UPDATE advisor_inference_job SET status='FAILED',failure_code='PROVIDER_UNAVAILABLE',"
                + "completed_at=UTC_TIMESTAMP(6),started_at=UTC_TIMESTAMP(6) WHERE id=? AND org_id=?",
                jobId, organizationId);
        jdbc.update("UPDATE advisor_inference_attempt SET status='FAILED',failure_code='PROVIDER_UNAVAILABLE'"
                + " WHERE job_id=? AND org_id=? AND attempt_no=1", jobId, organizationId);
        mockMvc.perform(post("/api/v1/ai-advisor/explanations/{id}/retry", jobId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isOk());
        var job = jdbc.queryForMap("SELECT status,gateway_request_id,failure_code,started_at,"
                + "completed_at,claim_token,claim_expires_at,attempt_count FROM advisor_inference_job"
                + " WHERE id=? AND org_id=?", jobId, organizationId);
        assertEquals("PENDING", job.get("status").toString());
        assertNull(job.get("gateway_request_id"));
        assertNull(job.get("failure_code"));
        assertNull(job.get("started_at"));
        assertNull(job.get("completed_at"));
        assertNull(job.get("claim_token"));
        assertEquals(2, ((Number) job.get("attempt_count")).intValue());
        var attempts = jdbc.queryForList("SELECT attempt_no,status FROM advisor_inference_attempt"
                + " WHERE job_id=? AND org_id=? ORDER BY attempt_no", jobId, organizationId);
        assertEquals(2, attempts.size());
        assertEquals("FAILED", attempts.get(0).get("status").toString());
        assertEquals("PENDING", attempts.get(1).get("status").toString());
    }

    private void putProfile(long providerModelId, long projectId, String scopeType, long scopeId,
            String budgetMode) throws Exception {
        mockMvc.perform(put("/api/v1/ai-advisor/profile")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"providerModelId\":" + providerModelId + ",\"projectId\":" + projectId
                                + ",\"financialScopeType\":\"" + scopeType + "\",\"financialScopeId\":" + scopeId
                                + ",\"budgetEnforcementMode\":\"" + budgetMode + "\"}"))
                .andExpect(status().isOk());
    }

    private long requestExplanation(String json) throws Exception {
        var body = mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long insertAnomaly(long orgId, String observed, String baseline, String delta) {
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,"
                + "created_at) VALUES (?,CURDATE(),'USD',1,'COMPLETED',UTC_TIMESTAMP(6))", orgId);
        var runId = jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("INSERT INTO cost_anomaly(org_id,run_id,grain_type,grain_key,currency,observed_amount,"
                + "baseline_amount,delta_amount,delta_percent,robust_z_score,drivers_json,created_at)"
                + " VALUES (?,?,'ORGANIZATION',?,'USD',?,?,?,0,3.5,"
                + "CAST('[{\"dimension\":\"PROVIDER\",\"key\":\"prov-a\",\"delta\":\"12.50\"}]' AS JSON),"
                + "UTC_TIMESTAMP(6))",
                orgId, runId, "org:" + orgId, new java.math.BigDecimal(observed),
                new java.math.BigDecimal(baseline), new java.math.BigDecimal(delta));
        return jdbc.queryForObject("SELECT id FROM cost_anomaly WHERE org_id=? AND run_id=?", Long.class,
                orgId, runId);
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
        jdbc.update("DELETE FROM provider_model WHERE provider_model_name LIKE 'ev-model-%'");
        jdbc.update("DELETE FROM model_catalog WHERE model_key LIKE 'ev-model-%'");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code='EV_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='EV_MANAGER'");
    }
}
