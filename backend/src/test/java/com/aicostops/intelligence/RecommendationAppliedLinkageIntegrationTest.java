package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
 * Recommendation lifecycle authorization and linkage over real MySQL (M18 round-3 P1):
 * read-only users cannot mutate business state, and mark-applied proves a same-organization
 * ACTIVE routing revision that actually contains the recommended candidate — recording only the
 * human-completed action, never creating policy.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET })
@AutoConfigureMockMvc
@Tag("integration")
class RecommendationAppliedLinkageIntegrationTest extends ControlPlaneFixtureSupport {

    @Autowired
    private MockMvc mockMvc;

    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long readerUserId;
    private long readerMemberId;
    private long logicalId;
    private long candidateAccount;
    private long candidateModel;
    private long policyId;
    private long wrongPolicyId;
    private long rec1;
    private long rec2;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Rec Org", "rec-org");
        managerUserId = insertUser("rec-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        readerUserId = insertUser("rec-reader@example.com");
        readerMemberId = insertMember(organizationId, readerUserId);
        logicalId = insertGlobalLogicalModel("rec-logical");
        candidateAccount = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Rec Account");
        candidateModel = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalId, "rec-wire");
        var otherLogical = insertGlobalLogicalModel("rec-logical-other");
        policyId = insertPolicy(logicalId);
        jdbc.update("INSERT INTO routing_policy_candidate(org_id,routing_policy_id,provider_account_id,"
                + "provider_model_id,priority,status,created_at)"
                + " VALUES (?,?,?,?,0,'ACTIVE',UTC_TIMESTAMP(6))",
                organizationId, policyId, candidateAccount, candidateModel);
        wrongPolicyId = insertPolicy(otherLogical);
        var yesterday = LocalDate.now().minusDays(1);
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,"
                + "created_at) VALUES (?,?,'USD',1,'COMPLETED',UTC_TIMESTAMP(6))", organizationId, yesterday);
        var runId = jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=?", Long.class,
                organizationId);
        rec1 = insertRecommendation(runId, logicalId);
        rec2 = insertRecommendation(runId, logicalId);
        createPermissionRole("REC_MANAGER", List.of("BUDGET_MANAGE", "COST_READ"));
        createPermissionRole("REC_READER", List.of("COST_READ"));
        assign(managerMemberId, "REC_MANAGER", "ORG", organizationId);
        assign(readerMemberId, "REC_READER", "ORG", organizationId);
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void appliedRequiresProvenRoutingLinkage() throws Exception {
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge", rec1)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/mark-applied", rec1)
                        .header("Authorization", bearerFor(managerUserId))
                        .param("routingPolicyId", String.valueOf(policyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.routingPolicyId").value((int) policyId))
                .andExpect(jsonPath("$.routingChangeRequired").value(false));
        assertEquals(policyId, jdbc.queryForObject("SELECT routing_policy_id FROM savings_recommendation"
                + " WHERE id=?", Long.class, rec1));
        // No routing revision was created or activated by the recommendation itself.
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM routing_policy WHERE org_id=?",
                Integer.class, organizationId));
    }

    @Test
    void wrongPolicyLinkageIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/mark-applied", rec2)
                        .header("Authorization", bearerFor(managerUserId))
                        .param("routingPolicyId", String.valueOf(wrongPolicyId)))
                .andExpect(status().isBadRequest());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM savings_recommendation WHERE id=?",
                String.class, rec2));
    }

    @Test
    void readOnlyUsersCannotMutateRecommendations() throws Exception {
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge", rec2)
                        .header("Authorization", bearerFor(readerUserId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/mark-applied", rec2)
                        .header("Authorization", bearerFor(readerUserId))
                        .param("routingPolicyId", String.valueOf(policyId)))
                .andExpect(status().isForbidden());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM savings_recommendation WHERE id=?",
                String.class, rec2));
    }

    private long insertPolicy(long logicalModelId) {
        jdbc.update("INSERT INTO routing_policy(org_id,model_id,version,status,created_at)"
                + " VALUES (?,?,1,'ACTIVE',UTC_TIMESTAMP(6))", organizationId, logicalModelId);
        return jdbc.queryForObject("SELECT id FROM routing_policy WHERE org_id=? AND model_id=?",
                Long.class, organizationId, logicalModelId);
    }

    private long insertRecommendation(long runId, long logical) {
        var yesterday = LocalDate.now().minusDays(1);
        jdbc.update("INSERT INTO savings_recommendation(org_id,run_id,logical_model_id,"
                + "current_provider_account_id,current_provider_model_id,current_pricing_version_id,"
                + "candidate_provider_account_id,candidate_provider_model_id,candidate_pricing_version_id,"
                + "currency,evidence_window_start,evidence_window_end,current_cost,candidate_cost,"
                + "potential_saving,potential_saving_percent,evidence_fingerprint,status,routing_policy_id,"
                + "routing_change_required,calculated_at,created_at)"
                + " VALUES (?,?,?,11,12,13,?,?,14,'USD',?,?,'100.00','60.00','40.00','40.00',"
                + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','OPEN',NULL,"
                + "FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                organizationId, runId, logical, candidateAccount, candidateModel, yesterday.minusDays(29),
                yesterday.minusDays(1));
        return jdbc.queryForObject("SELECT MAX(id) FROM savings_recommendation WHERE org_id=? AND run_id=?",
                Long.class, organizationId, runId);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM savings_recommendation");
        jdbc.update("DELETE FROM cost_intelligence_run");
        jdbc.update("DELETE FROM routing_policy_candidate");
        jdbc.update("DELETE FROM routing_policy");
        jdbc.update("DELETE FROM provider_model WHERE provider_model_name LIKE 'rec-wire%'");
        jdbc.update("DELETE FROM model_catalog WHERE model_key LIKE 'rec-logical%'");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code IN ('REC_MANAGER','REC_READER')");
        jdbc.update("DELETE FROM `role` WHERE code IN ('REC_MANAGER','REC_READER')");
    }
}
