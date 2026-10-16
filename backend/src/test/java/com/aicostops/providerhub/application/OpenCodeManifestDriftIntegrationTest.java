package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OpenCode manifest drift repair over real MySQL 8.4 Testcontainers (M-2, 2026-10-16).
 *
 * <p>Proves the classification decision reaches persisted discovery truth, that the V1 Zen
 * template's promotion gate admits exactly the newly evidenced Chat models, that Console-only ids
 * stay fail closed, and that the repair neither creates pricing nor discards the lineage of
 * retired ids.
 *
 * <p><b>No billable inference is issued.</b> The built-in V1 Zen profile is server-owned and its
 * base URL is the real {@code https://opencode.ai/zen/v1}; every path exercised here is rejected by
 * the manifest gate <em>before</em> any Provider I/O, so this suite never opens a socket to
 * OpenCode. Rejection is asserted at the API boundary (HTTP status) and at the manifest boundary.
 * The probe happy path is deliberately not exercised against the built-in template; it is covered
 * by the loopback-backed {@code AuthenticatedConnectionProbeIntegrationTest} and
 * {@code StreamingCapabilityProbeIntegrationTest} on custom connections.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class OpenCodeManifestDriftIntegrationTest extends ControlPlaneFixtureSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpenCodeModelManifest manifest;

    private long org;
    private long user;
    private long member;
    private long account;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        reseedCustomCatalog();
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('OPENCODE_ZEN','OpenCode Zen',"
                + "'OPENCODE_ZEN','https://opencode.ai/zen/v1','ACTIVE',JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)");
        org = insertOrganization("Drift Org", "drift-org");
        user = insertUser("drift-admin@example.com");
        member = insertMember(org, user);
        account = insertAccount(org, "OPENCODE_ZEN", "Drift Account");
        createPermissionRole("DRIFT_MANAGER",
                List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(member, "DRIFT_MANAGER", "ORG", org);
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** Newly evidenced paid Chat models persist as CHAT/PAID on the V1 Zen template. */
    @Test
    void newlyEvidencedPaidChatModelsPersistAsChatPaid() throws Exception {
        var conn = openCodeConnection();
        for (var model : List.of("deepseek-v4.1-flash", "glm-5.3", "glm-5.3-flash",
                "mistral-large-4", "qwen3.8-max")) {
            manualModel(conn, model);
            assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, model), model);
            assertEquals("PAID", pricingOf(conn, model), model);
        }
    }

    /** Newly evidenced free Chat models persist as CHAT/VERIFIED_FREE. */
    @Test
    void newlyEvidencedFreeChatModelsPersistAsVerifiedFree() throws Exception {
        var conn = openCodeConnection();
        for (var model : List.of("longcat-2.5-preview-free", "mimo-v2.6-flash-free",
                "nemotron-3.5-lightning-free", "space-bunny-free")) {
            manualModel(conn, model);
            assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, model), model);
            assertEquals("VERIFIED_FREE", pricingOf(conn, model), model);
        }
    }

    /**
     * Ids with no V1 Zen Chat attestation stay AVAILABLE/UNKNOWN and are rejected by both gates: a
     * V2 Console free listing never purchases V1 Zen Chat admission, and the rejection precedes any
     * Provider I/O. The first four have no V1 evidence at all; the last two carry a V1 endpoint row
     * in the Messages family, which this project does not implement.
     */
    @Test
    void unadmittedModelsStayUnknownAndCannotProbeOrPromote() throws Exception {
        var conn = openCodeConnection();
        for (var model : List.of("deepseek-v4.1-flash-free", "cat-jam-free",
                "muse-spark-1.2-contributor-free", "nemotron-3.5-super-vl-free",
                "claude-sonnet-5-5", "qwen3.8-flash")) {
            var discovery = manualModel(conn, model);
            assertEquals("UNKNOWN", protoOf(conn, model), model);
            assertEquals("UNKNOWN", pricingOf(conn, model), model);
            assertEquals("AVAILABLE", availOf(conn, model), model);
            assertFalse(manifest.isChatCompatible(model),
                    () -> model + " is a Console-only listing and must never reach the Chat path");
            // Both gates reject before the transport is ever touched, so no request can be billed.
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isConflict());
        }
    }

    /**
     * A newly evidenced <em>free</em> Chat model passes the free-admission gate end to end: with the
     * probe verdict recorded, promotion succeeds while still creating zero pricing versions.
     *
     * <p>Updated for FREE_ONLY (2026-10-16): this case previously used the PAID {@code glm-5.3},
     * which the manifest still classifies as Chat/PAID but which no longer holds a dispatch right.
     * The paid counterpart is now asserted as refused by
     * {@link OpenCodeFreeOnlyAdmissionIntegrationTest}.
     */
    @Test
    void newlyEvidencedFreeChatModelPromotesWithZeroPricingCreated() throws Exception {
        var conn = openCodeConnection();
        var discovery = manualModel(conn, "space-bunny-free");
        assertTrue(manifest.isFreeEligible("space-bunny-free"),
                "The free-admission gate must admit the newly evidenced free model");
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                + " verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                + " WHERE id=? AND org_id=?", discovery, org);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                        .header("Authorization", bearerFor(user)))
                .andExpect(status().isCreated());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pricing_version WHERE org_id=?",
                Integer.class, org).intValue(),
                "PAID/VERIFIED_FREE classification must never auto-create a Pricing Version");
    }

    /**
     * A newly evidenced PAID Chat model keeps its reviewed classification but is refused by the
     * free-admission gate, so the drift repair can never widen paid dispatch.
     */
    @Test
    void newlyEvidencedPaidChatModelIsRefusedByFreeAdmission() throws Exception {
        var conn = openCodeConnection();
        for (var model : List.of("deepseek-v4.1-flash", "glm-5.3", "qwen3.8-max")) {
            var discovery = manualModel(conn, model);
            assertTrue(manifest.isChatCompatible(model), model);
            assertFalse(manifest.isFreeEligible(model), model);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isBadRequest());
            jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                    + " verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                    + " WHERE id=? AND org_id=?", discovery, org);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isConflict());
        }
    }

    /**
     * Retired ids keep their reviewed lineage so historical billing stays interpretable, and the
     * repair deletes no discovery row.
     */
    @Test
    void retiredIdsKeepTheirReviewedLineage() throws Exception {
        var conn = openCodeConnection();
        var retired = List.of("deepseek-v4-flash-free", "laguna-s-2.1-free", "ling-3.0-tiny-free",
                "longcat-2.0-free", "north-mini-code-free");
        for (var model : retired) {
            manualModel(conn, model);
            assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, model), model);
            assertEquals("VERIFIED_FREE", pricingOf(conn, model), model);
        }
        assertEquals(retired.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_model_discovery WHERE org_id=? AND"
                        + " provider_connection_profile_id=?", Integer.class, org, conn).intValue(),
                "Every observation must survive the repair (no lineage deletion)");
    }

    /** Non-Chat families are never promoted into Chat by the drift repair. */
    @Test
    void responsesAndMessagesFamiliesStillNeverBecomeChat() throws Exception {
        var conn = openCodeConnection();
        for (var model : List.of("gpt-6-astra", "grok-4.6", "claude-fable-5-1", "qwen3.8-flash")) {
            var discovery = manualModel(conn, model);
            assertEquals("UNKNOWN", protoOf(conn, model),
                    () -> model + " has no V1 Zen Chat attestation and must fail closed");
            assertFalse(manifest.isChatCompatible(model), model);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isConflict());
        }
    }

    /** The V1 Zen template still pins the V1 origin and never the V2 Console family. */
    @Test
    void v1ZenTemplateIsUnchangedByTheDriftRepair() {
        var template = new ProviderTemplateRegistry().require(ProviderTemplateRegistry.OPENCODE_ZEN);
        assertEquals("https://opencode.ai/zen/v1", template.baseUrl());
        assertEquals("/chat/completions", template.completionPath());
        assertEquals("/models", template.modelsPath());
        assertEquals("DIRECT_ONLY", template.networkPolicy());
        assertEquals("BEARER", template.authType());
        assertFalse(template.baseUrl().contains("/inference"));
    }

    private long openCodeConnection() throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"providerAccountId\":" + account
                                + ",\"templateCode\":\"OPENCODE_ZEN\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertTrue(id > 0, "A created connection must have a positive id");
        return id;
    }

    private long manualModel(long conn, String name) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", conn)
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"modelName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String protoOf(long conn, String name) {
        return jdbc.queryForObject("SELECT protocol_code FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?",
                String.class, org, conn, name);
    }

    private String pricingOf(long conn, String name) {
        return jdbc.queryForObject("SELECT pricing_classification FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?",
                String.class, org, conn, name);
    }

    private String availOf(long conn, String name) {
        return jdbc.queryForObject("SELECT availability FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?",
                String.class, org, conn, name);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM provider_model_discovery");
        jdbc.update("DELETE FROM provider_connection_profile");
        jdbc.update("UPDATE provider_credential SET predecessor_credential_id=NULL");
        jdbc.update("DELETE FROM provider_credential");
        jdbc.update("DELETE FROM provider_model WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM model_catalog WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code='DRIFT_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='DRIFT_MANAGER'");
    }
}
