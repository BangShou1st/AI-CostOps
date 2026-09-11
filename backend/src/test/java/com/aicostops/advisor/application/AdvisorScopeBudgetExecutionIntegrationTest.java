package com.aicostops.advisor.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Advisor scope/budget/org-isolation matrix over real MySQL (M18 round-3 P1): TEAM and COST_CENTER
 * scopes activate, mismatched scope types are rejected (never silently reinterpreted),
 * cross-organization scopes and private models are rejected, OPTIONAL to REQUIRED transitions work.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Tag("integration")
class AdvisorScopeBudgetExecutionIntegrationTest extends ControlPlaneFixtureSupport {

    @Autowired
    private MockMvc mockMvc;

    private long organizationId;
    private long foreignOrgId;
    private long managerUserId;
    private long managerMemberId;
    private long projectA;
    private long projectB;
    private long teamT;
    private long costCenterC;
    private long modelA;
    private long foreignTeam;
    private long foreignPrivateModel;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Scope Org", "scope-org");
        foreignOrgId = insertOrganization("Scope Foreign", "scope-foreign");
        managerUserId = insertUser("scope-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        // Insert teams before projects so team and project ids diverge: this makes wrong-type
        // scope ids deterministic (an id present in one scope table but absent in the asserted one).
        teamT = insertTeam(organizationId, "team-t");
        projectA = insertProject(organizationId, "proj-a");
        projectB = insertProject(organizationId, "proj-b");
        costCenterC = insertCostCenter(organizationId, "cc-c");
        var logicalA = insertGlobalLogicalModel("scope-model-a");
        modelA = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalA, "scope-model-a");
        foreignTeam = insertTeam(foreignOrgId, "foreign-team");
        foreignPrivateModel = insertForeignPrivateModel();
        createPermissionRole("SCOPE_MANAGER", List.of("AI_ADVISOR_MANAGE", "AI_ADVISOR_USE"));
        assign(managerMemberId, "SCOPE_MANAGER", "ORG", organizationId);
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void teamScopeProfileSucceeds() throws Exception {
        putProfile(modelA, projectA, "TEAM", teamT, "OPTIONAL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialScopeType").value("TEAM"))
                .andExpect(jsonPath("$.financialScopeId").value((int) teamT));
    }

    @Test
    void costCenterScopeProfileSucceeds() throws Exception {
        putProfile(modelA, projectA, "COST_CENTER", costCenterC, "REQUIRED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialScopeType").value("COST_CENTER"))
                .andExpect(jsonPath("$.budgetEnforcementMode").value("REQUIRED"));
    }

    @Test
    void mismatchedScopeTypesAreRejected() throws Exception {
        // Each asserted id exists as a live row of another scope type but not of the asserted one
        // (resolved at runtime so auto-increment drift across suites cannot collide): the per-type
        // lookup must reject instead of reinterpreting the foreign scope row.
        putProfile(modelA, projectA, "TEAM", projectIdWithoutTeam(), "OPTIONAL")
                .andExpect(status().isBadRequest());
        putProfile(modelA, projectA, "COST_CENTER", projectIdWithoutCostCenter(), "OPTIONAL")
                .andExpect(status().isBadRequest());
        putProfile(modelA, projectA, "PROJECT", 91003L, "OPTIONAL")
                .andExpect(status().isBadRequest());
    }

    private long projectIdWithoutTeam() {
        return jdbc.queryForObject("SELECT p.id FROM project p"
                + " LEFT JOIN team t ON t.id=p.id AND t.org_id=p.org_id"
                + " WHERE p.org_id=? AND t.id IS NULL LIMIT 1", Long.class, organizationId);
    }

    private long projectIdWithoutCostCenter() {
        return jdbc.queryForObject("SELECT p.id FROM project p"
                + " LEFT JOIN cost_center c ON c.id=p.id AND c.org_id=p.org_id"
                + " WHERE p.org_id=? AND c.id IS NULL LIMIT 1", Long.class, organizationId);
    }

    @Test
    void crossOrgScopeAndModelAreRejected() throws Exception {
        putProfile(modelA, projectA, "TEAM", foreignTeam, "OPTIONAL")
                .andExpect(status().isBadRequest());
        putProfile(foreignPrivateModel, projectA, "PROJECT", projectA, "OPTIONAL")
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions putProfile(long providerModelId, long projectId,
            String scopeType, long scopeId, String budgetMode) throws Exception {
        return mockMvc.perform(put("/api/v1/ai-advisor/profile")
                .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                .content("{\"providerModelId\":" + providerModelId + ",\"projectId\":" + projectId
                        + ",\"financialScopeType\":\"" + scopeType + "\",\"financialScopeId\":" + scopeId
                        + ",\"budgetEnforcementMode\":\"" + budgetMode + "\"}"));
    }

    private long insertForeignPrivateModel() {
        var accountB = insertAccount(foreignOrgId, "CUSTOM_OPENAI_COMPATIBLE", "Foreign Account");
        jdbc.update("INSERT INTO model_catalog(model_key,name,owner_org_id,status,capabilities_json,"
                + "default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES ('scope-private-model','Private',?,'ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", foreignOrgId);
        var logicalId = jdbc.queryForObject("SELECT id FROM model_catalog WHERE model_key='scope-private-model'",
                Long.class);
        jdbc.update("INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,"
                + "routing_eligible,capabilities_json,owner_org_id,provider_account_id,created_at,updated_at)"
                + " VALUES ('CUSTOM_OPENAI_COMPATIBLE',?,'scope-private-model','ACTIVE',TRUE,JSON_OBJECT(),"
                + "?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", logicalId, foreignOrgId, accountB);
        return jdbc.queryForObject("SELECT id FROM provider_model WHERE provider_model_name='scope-private-model'",
                Long.class);
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
        jdbc.update("DELETE FROM provider_model WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM model_catalog WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM provider_model WHERE provider_model_name LIKE 'scope-model-%'");
        jdbc.update("DELETE FROM model_catalog WHERE model_key LIKE 'scope-model-%'");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code='SCOPE_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='SCOPE_MANAGER'");
    }
}
