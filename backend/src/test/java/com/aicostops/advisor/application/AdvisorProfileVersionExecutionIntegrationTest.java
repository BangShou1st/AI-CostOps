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
 * Frozen advisor execution semantics over real MySQL (M18 round-3 P0 matrix): a job requested
 * under profile v1 (OPTIONAL/projectA/modelA) never silently executes under a later v2
 * (REQUIRED/projectB/modelB) — it fails deterministically as superseded — while new requests
 * bind exactly to v2 with a rotated execution principal.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Tag("integration")
class AdvisorProfileVersionExecutionIntegrationTest extends ControlPlaneFixtureSupport {

    @Autowired
    private MockMvc mockMvc;

    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long projectA;
    private long projectB;
    private long teamT;
    private long modelA;
    private long modelB;
    private long budgetA;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Advisor Org", "advisor-org");
        managerUserId = insertUser("advisor-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        projectA = insertProject(organizationId, "proj-a");
        projectB = insertProject(organizationId, "proj-b");
        teamT = insertTeam(organizationId, "team-t");
        var logicalA = insertGlobalLogicalModel("adv-model-a");
        var logicalB = insertGlobalLogicalModel("adv-model-b");
        modelA = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalA, "adv-model-a");
        modelB = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalB, "adv-model-b");
        budgetA = insertBudget(organizationId, "PROJECT", projectA, "USD", "100.00");
        createPermissionRole("ADV_MANAGER", List.of("AI_ADVISOR_MANAGE", "AI_ADVISOR_USE"));
        assign(managerMemberId, "ADV_MANAGER", "ORG", organizationId);
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void oldJobNeverSilentlyExecutesUnderNewProfile() throws Exception {
        var v1 = putProfile(modelA, projectA, "PROJECT", projectA, "OPTIONAL");
        var job1 = requestExplanation("BUDGET_RISK", budgetA);
        assertEquals("PENDING", jobStatus(job1));
        assertEquals(v1, jobProfileId(job1));
        var v2 = putProfile(modelB, projectB, "TEAM", teamT, "REQUIRED");
        assertNotEquals(v1, v2);
        // The undispatched v1 job is deterministically superseded, never rebound to v2.
        assertEquals("FAILED", jobStatus(job1));
        assertEquals("PROFILE_SUPERSEDED", jobFailureCode(job1));
        // New requests bind exactly to v2.
        var job2 = requestExplanation("BUDGET_RISK", budgetA);
        assertEquals("PENDING", jobStatus(job2));
        assertEquals(v2, jobProfileId(job2));
        // Execution principal rotated exactly: v2 credential ACTIVE with v2 scope/mode.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM gateway_credential"
                + " WHERE org_id=? AND credential_origin='INTERNAL_SYSTEM' AND status='ACTIVE'"
                + " AND project_id=? AND financial_scope_type='TEAM' AND financial_scope_id=?"
                + " AND budget_enforcement_mode='REQUIRED' AND advisor_profile_id=?",
                Integer.class, organizationId, projectB, teamT, v2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM gateway_credential"
                + " WHERE org_id=? AND credential_origin='INTERNAL_SYSTEM' AND status='ACTIVE'"
                + " AND advisor_profile_id=?", Integer.class, organizationId, v1));
    }

    private long putProfile(long providerModelId, long projectId, String scopeType, long scopeId,
            String budgetMode) throws Exception {
        var body = mockMvc.perform(put("/api/v1/ai-advisor/profile")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"providerModelId\":" + providerModelId + ",\"projectId\":" + projectId
                                + ",\"financialScopeType\":\"" + scopeType + "\",\"financialScopeId\":" + scopeId
                                + ",\"budgetEnforcementMode\":\"" + budgetMode + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long requestExplanation(String subjectType, long subjectId) throws Exception {
        var body = mockMvc.perform(post("/api/v1/ai-advisor/explanations")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"subjectType\":\"" + subjectType + "\",\"subjectId\":" + subjectId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String jobStatus(long jobId) throws Exception {
        var body = mockMvc.perform(get("/api/v1/ai-advisor/explanations/{id}", jobId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.status");
    }

    private String jobFailureCode(long jobId) throws Exception {
        var body = mockMvc.perform(get("/api/v1/ai-advisor/explanations/{id}", jobId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.failureCode");
    }

    private long jobProfileId(long jobId) {
        return jdbc.queryForObject("SELECT advisor_profile_id FROM advisor_inference_job"
                + " WHERE id=? AND org_id=?", Long.class, jobId, organizationId);
    }

    private long insertBudget(long orgId, String scopeType, long scopeId, String currency, String total) {
        var periods = jdbc.query("SELECT id FROM billing_period WHERE org_id=? LIMIT 1",
                (rs, i) -> rs.getLong(1), orgId);
        Long periodId = periods.isEmpty() ? null : periods.get(0);
        if (periodId == null) {
            jdbc.update("INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at)"
                    + " VALUES (?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY),'OPEN',"
                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
            periodId = jdbc.queryForObject("SELECT id FROM billing_period WHERE org_id=? LIMIT 1", Long.class,
                    orgId);
        }
        jdbc.update("INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,"
                + "actual_amount,committed_amount,status,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,0,0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                orgId, periodId, scopeType, scopeId, currency, new java.math.BigDecimal(total));
        return jdbc.queryForObject("SELECT id FROM budget WHERE org_id=? AND scope_type=? AND scope_id=?",
                Long.class, orgId, scopeType, scopeId);
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
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM billing_period");
        jdbc.update("DELETE FROM provider_model WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM model_catalog WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM provider_model WHERE provider_model_name LIKE 'adv-model-%'");
        jdbc.update("DELETE FROM model_catalog WHERE model_key LIKE 'adv-model-%'");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code='ADV_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='ADV_MANAGER'");
    }
}
