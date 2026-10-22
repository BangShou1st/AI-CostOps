package com.aicostops.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.audit.application.AuditService;
import com.aicostops.providerhub.application.ControlPlaneFixtureSupport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Recommendation command boundaries over real MySQL (post-split coverage for M18 round-3 P1): the
 * audit append and the conditional status update share one transaction, another organization's
 * manager can never mutate a foreign recommendation, and repeated or illegal transitions are
 * rejected without touching state.
 *
 * <p>Intentionally no test-managed transaction: the audit-failure case must exercise a real
 * rollback of the command transaction, which a test-level rollback would hide.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET })
@AutoConfigureMockMvc
@Tag("integration")
class SavingsRecommendationCommandAtomicityIntegrationTest extends ControlPlaneFixtureSupport {
    @org.springframework.beans.factory.annotation.Autowired
    private com.aicostops.intelligence.application.CostAnalysisInputService inputs;


    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private AuditService audit;

    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long logicalModelId;
    private long candidateAccountId;
    private long candidateModelId;
    private long policyId;
    private long recommendationId;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        organizationId = insertOrganization("Command Org", "command-org");
        managerUserId = insertUser("command-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        createPermissionRole("CMD_MANAGER", List.of("BUDGET_MANAGE", "COST_READ"));
        assign(managerMemberId, "CMD_MANAGER", "ORG", organizationId);
        logicalModelId = insertGlobalLogicalModel("cmd-logical");
        candidateAccountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Command Account");
        candidateModelId = insertGlobalProviderModel("CUSTOM_OPENAI_COMPATIBLE", logicalModelId,
                "cmd-wire");
        policyId = insertActivePolicyWithCandidate();
        recommendationId = insertRecommendation();
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void failingAuditAppendRollsBackTheStatusTransition() throws Exception {
        doThrow(new IllegalStateException("simulated audit outage"))
                .when(audit).append(any(), any(), any(), any(), any(), any());

        var failure = assertThrows(Exception.class, () -> mockMvc.perform(
                post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge", recommendationId)
                        .header("Authorization", bearerFor(managerUserId))));
        assertInstanceOf(IllegalStateException.class, rootCause(failure),
                () -> "expected the injected audit failure, got " + failure);

        // The audit write is part of the command transaction: no ACKNOWLEDGED state survives it.
        assertEquals("OPEN", statusOf(recommendationId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?",
                Integer.class, organizationId));
    }

    @Test
    void foreignOrganizationManagerNeverMutatesAnotherOrganizationsRecommendation() throws Exception {
        var foreignOrg = insertOrganization("Command Foreign", "command-foreign");
        var foreignUserId = insertUser("command-foreign@example.com");
        var foreignMemberId = insertMember(foreignOrg, foreignUserId);
        createPermissionRole("CMD_FOREIGN_MANAGER", List.of("BUDGET_MANAGE", "COST_READ"));
        assign(foreignMemberId, "CMD_FOREIGN_MANAGER", "ORG", foreignOrg);
        flushRedis();

        // The conditional update is org-scoped: a foreign manager changes exactly zero rows.
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge",
                        recommendationId)
                        .header("Authorization", bearerFor(foreignUserId)))
                .andExpect(status().isConflict());

        assertEquals("OPEN", statusOf(recommendationId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?",
                Integer.class, foreignOrg));
    }

    @Test
    void repeatedAndIllegalTransitionsAreRejectedWithoutChangingState() throws Exception {
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge",
                        recommendationId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        // OPEN -> ACKNOWLEDGED can never be replayed.
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge",
                        recommendationId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isConflict());
        assertEquals("ACKNOWLEDGED", statusOf(recommendationId));

        // APPLIED is unreachable while the recommendation is still OPEN, even when the routing
        // linkage itself would be valid.
        var stillOpen = insertRecommendation();
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/mark-applied", stillOpen)
                        .header("Authorization", bearerFor(managerUserId))
                        .param("routingPolicyId", String.valueOf(policyId)))
                .andExpect(status().isConflict());
        assertEquals("OPEN", statusOf(stillOpen));

        // A dismissed recommendation can never be acknowledged afterwards.
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/dismiss",
                        recommendationId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        mockMvc.perform(post("/api/v1/cost-intelligence/recommendations/{id}/acknowledge",
                        recommendationId)
                        .header("Authorization", bearerFor(managerUserId)))
                .andExpect(status().isConflict());
        assertEquals("DISMISSED", statusOf(recommendationId));
    }

    private static Throwable rootCause(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM savings_recommendation WHERE id=?", String.class, id);
    }

    /** ACTIVE policy over the same logical model whose candidate set contains the recommendation. */
    private long insertActivePolicyWithCandidate() {
        jdbc.update("INSERT INTO routing_policy(org_id,model_id,version,status,created_at)"
                + " VALUES (?,?,1,'ACTIVE',UTC_TIMESTAMP(6))", organizationId, logicalModelId);
        var policy = jdbc.queryForObject("SELECT id FROM routing_policy WHERE org_id=? AND model_id=?",
                Long.class, organizationId, logicalModelId);
        jdbc.update("INSERT INTO routing_policy_candidate(org_id,routing_policy_id,provider_account_id,"
                + "provider_model_id,priority,status,created_at)"
                + " VALUES (?,?,?,?,0,'ACTIVE',UTC_TIMESTAMP(6))",
                organizationId, policy, candidateAccountId, candidateModelId);
        return policy;
    }

    private long insertRecommendation() {
        var runId = analysisRunId();
        var yesterday = LocalDate.now().minusDays(1);
        jdbc.update("INSERT INTO savings_recommendation(org_id,run_id,logical_model_id,"
                + "current_provider_account_id,current_provider_model_id,current_pricing_version_id,"
                + "candidate_provider_account_id,candidate_provider_model_id,candidate_pricing_version_id,"
                + "currency,evidence_window_start,evidence_window_end,current_cost,candidate_cost,"
                + "potential_saving,potential_saving_percent,evidence_fingerprint,status,routing_policy_id,"
                + "routing_change_required,calculated_at,created_at)"
                + " VALUES (?,?,?,?,?,33,?,?,43,'USD',?,?,'100.00','60.00','40.00','40.00',"
                + "?,'OPEN',NULL,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                organizationId, runId, logicalModelId, candidateAccountId, candidateModelId,
                candidateAccountId, candidateModelId, yesterday.minusDays(29), yesterday.minusDays(1),
                "a".repeat(64));
        return jdbc.queryForObject("SELECT MAX(id) FROM savings_recommendation WHERE org_id=? AND run_id=?",
                Long.class, organizationId, runId);
    }

    /** The current analysis input identity for today's "yesterday" is shared by both rows. */
    private long analysisRunId() {
        var yesterday = LocalDate.now().minusDays(1);
        var existing = jdbc.query("SELECT id FROM cost_intelligence_run WHERE org_id=? AND analysis_date=?"
                + " AND currency='USD' AND run_version=?", (rs, i) -> rs.getLong(1), organizationId,
                yesterday, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        jdbc.update("UPDATE cost_intelligence_run SET input_fingerprint=? WHERE org_id=? AND run_version=?",
                inputs.currentFingerprint(organizationId, "USD", yesterday, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION),
                organizationId, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbc.update("INSERT INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,"
                + "created_at) VALUES (?,?,'USD',?,'COMPLETED',UTC_TIMESTAMP(6))", organizationId, yesterday,
                com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        jdbc.update("UPDATE cost_intelligence_run SET input_fingerprint=? WHERE org_id=? AND run_version=?",
                inputs.currentFingerprint(organizationId, "USD", yesterday, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION),
                organizationId, com.aicostops.intelligence.application.CostIntelligenceValidation.RUN_VERSION);
        return jdbc.queryForObject("SELECT id FROM cost_intelligence_run WHERE org_id=?", Long.class,
                organizationId);
    }

    private void cleanDatabase() {
        // The intelligence suites share one MySQL Testcontainer, so a local
        // DELETE list that misses a foreign-key child of app_user
        // (user_credential, ...) breaks on rows another suite left behind.
        // The shared cleaner removes children before parents in one FK-safe pass.
        com.aicostops.testsupport.M2DatabaseCleaner.clean(jdbc);
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code IN ('CMD_MANAGER','CMD_FOREIGN_MANAGER')");
        jdbc.update("DELETE FROM `role` WHERE code IN ('CMD_MANAGER','CMD_FOREIGN_MANAGER')");
    }
}
