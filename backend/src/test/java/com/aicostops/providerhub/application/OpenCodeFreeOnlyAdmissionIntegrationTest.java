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
 * OPENCODE_ZEN FREE_ONLY admission at the Backend probe and promotion boundary (2026-10-16),
 * over real MySQL 8.4 Testcontainers.
 *
 * <p>Proves that only models with currently valid free evidence can take the real Provider probe or
 * become routable, and that a PAID/UNKNOWN/UNSUPPORTED/delisted model is refused <em>before</em>
 * any Provider HTTP I/O — so no paid request can be issued even by an actor holding
 * {@code PROVIDER_ACCOUNT_MANAGE}.
 *
 * <p><b>No billable inference is issued.</b> The built-in profile is server-owned with the real
 * {@code https://opencode.ai/zen/v1} base URL, and every rejected path terminates at the manifest
 * gate before the transport is reached. Rejection is asserted at the API boundary and in the
 * persisted discovery/probe state, which is only mutated after a successful probe.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class OpenCodeFreeOnlyAdmissionIntegrationTest extends ControlPlaneFixtureSupport {

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
        org = insertOrganization("FreeOnly Org", "freeonly-org");
        user = insertUser("freeonly-admin@example.com");
        member = insertMember(org, user);
        account = insertAccount(org, "OPENCODE_ZEN", "FreeOnly Account");
        createPermissionRole("FREEONLY_MANAGER",
                List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(member, "FREEONLY_MANAGER", "ORG", org);
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /**
     * A free Chat model with current evidence stays fully usable: the manifest admits it and the
     * promotion path succeeds with a recorded probe verdict, creating zero pricing versions.
     */
    @Test
    void admittedFreeChatModelRemainsUsable() throws Exception {
        var conn = openCodeConnection();
        for (var model : OpenCodeFreeOnlyAdmissionTestFixtures.ADMITTED) {
            var discovery = manualModel(conn, model);
            assertTrue(manifest.isFreeEligible(model), model);
            assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, model), model);
            assertEquals("VERIFIED_FREE", pricingOf(conn, model), model);
            jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                    + " verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                    + " WHERE id=? AND org_id=?", discovery, org);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isCreated());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pricing_version WHERE org_id=?",
                Integer.class, org).intValue(),
                "Free admission must never auto-create a Pricing Version");
    }

    /**
     * PAID models are refused by both gates even though they are Chat classified and even when a
     * historical probe verdict has been stamped on the row: the rejection precedes Provider I/O and
     * no route becomes available.
     */
    @Test
    void paidChatModelsCannotProbeOrPromoteEvenWithHistoricalProbeEvidence() throws Exception {
        var conn = openCodeConnection();
        for (var model : OpenCodeFreeOnlyAdmissionTestFixtures.PAID) {
            var discovery = manualModel(conn, model);
            assertEquals("PAID", pricingOf(conn, model),
                    () -> model + " keeps its reviewed PAID classification as metadata");
            assertTrue(manifest.isChatCompatible(model), model);
            assertFalse(manifest.isFreeEligible(model), model);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isBadRequest());
            // Simulate a probe that succeeded under the pre-FREE_ONLY rules.
            jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                    + " verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                    + " WHERE id=? AND org_id=?", discovery, org);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isConflict());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM provider_model WHERE owner_org_id=?",
                Integer.class, org).intValue(),
                "A PAID model must never become a routable provider model");
    }

    /**
     * A rejected probe must not write probe evidence. {@code last_probed_at} staying NULL proves the
     * request ended before the Provider call rather than being refused after it.
     */
    @Test
    void rejectedProbeLeavesNoProviderEvidenceAndIssuesNoUpstreamCall() throws Exception {
        var conn = openCodeConnection();
        for (var model : List.of("deepseek-v4-pro", "qwen3.8-max", "future-unknown-model-xyz",
                "gpt-5.6-sol", "deepseek-v4.1-flash-free")) {
            var discovery = manualModel(conn, model);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isBadRequest());
            assertEquals(null, jdbc.queryForObject(
                    "SELECT last_probed_at FROM provider_model_discovery WHERE id=? AND org_id=?",
                    java.time.LocalDateTime.class, discovery, org),
                    () -> model + " must be rejected before any Provider HTTP I/O");
            assertEquals(null, jdbc.queryForObject(
                    "SELECT last_probe_status FROM provider_model_discovery WHERE id=? AND org_id=?",
                    String.class, discovery, org), model);
        }
    }

    /**
     * UNKNOWN, UNSUPPORTED, Console-only and delisted ids all fail closed; the delisted case is the
     * subtle one, because its reviewed classification is a genuine {@code VERIFIED_FREE} Chat entry
     * that simply is not served any more.
     */
    @Test
    void unknownUnsupportedConsoleOnlyAndDelistedModelsAllFailClosed() throws Exception {
        var conn = openCodeConnection();
        for (var model : OpenCodeFreeOnlyAdmissionTestFixtures.NOT_ADMITTED_MIXED) {
            var discovery = manualModel(conn, model);
            assertFalse(manifest.isFreeEligible(model), model);
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                            .header("Authorization", bearerFor(user)))
                    .andExpect(status().isConflict());
        }
    }

    /** Delisted free Chat ids keep their lineage while losing admission. */
    @Test
    void delistedFreeChatIdsKeepLineageWithoutAdmission() throws Exception {
        var conn = openCodeConnection();
        for (var model : OpenCodeFreeOnlyAdmissionTestFixtures.DELISTED_FREE) {
            manualModel(conn, model);
            assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, model), model);
            assertEquals("VERIFIED_FREE", pricingOf(conn, model),
                    () -> model + " must keep its reviewed classification (no lineage rewrite)");
            assertEquals("AVAILABLE", availOf(conn, model), model);
            assertFalse(manifest.isFreeEligible(model), model);
        }
        assertEquals(OpenCodeFreeOnlyAdmissionTestFixtures.DELISTED_FREE.size(),
                jdbc.queryForObject("SELECT COUNT(*) FROM provider_model_discovery WHERE org_id=?"
                        + " AND provider_connection_profile_id=?", Integer.class, org, conn).intValue(),
                "Free admission must never delete an observed row");
    }

    /** Custom OpenAI-compatible connections are out of scope and keep working unchanged. */
    @Test
    void customConnectionsAreUnaffectedByFreeOnlyAdmission() throws Exception {
        var custom = insertAccount(org, "CUSTOM_OPENAI_COMPATIBLE", "Custom Account");
        var body = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", bearerFor(user)).contentType("application/json")
                        .content("{\"providerAccountId\":" + custom
                                + ",\"templateCode\":\"CUSTOM_OPENAI_COMPATIBLE\","
                                + "\"baseUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var conn = ((Number) JsonPath.read(body, "$.id")).longValue();
        // The same paid name a built-in OpenCode connection would refuse is acceptable here,
        // because a custom connection is its own explicit Chat contract.
        manualModel(conn, "deepseek-v4-pro");
        assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, "deepseek-v4-pro"));
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                + " verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                + " WHERE org_id=? AND provider_connection_profile_id=?", org, conn);
        var discovery = jdbc.queryForObject("SELECT id FROM provider_model_discovery WHERE org_id=?"
                + " AND provider_connection_profile_id=?", Long.class, org, conn);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, discovery)
                        .header("Authorization", bearerFor(user)))
                .andExpect(status().isCreated());
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
                + " WHERE r.code='FREEONLY_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='FREEONLY_MANAGER'");
    }

    /** Shared, explicitly listed model sets so the assertions stay auditable against the evidence. */
    static final class OpenCodeFreeOnlyAdmissionTestFixtures {
        static final List<String> ADMITTED = List.of(
                "big-pickle", "longcat-2.5-preview-free", "mimo-v2.6-flash-free",
                "nemotron-3.5-lightning-free", "space-bunny-free");
        static final List<String> PAID = List.of(
                "deepseek-v4-pro", "deepseek-v4.1-flash", "glm-5.3", "mistral-large-4", "qwen3.8-max");
        static final List<String> DELISTED_FREE = List.of(
                "mimo-v2.5-free", "laguna-s-2.1-free", "ling-3.0-tiny-free", "longcat-2.0-free",
                "north-mini-code-free", "nemotron-3-ultra-free", "deepseek-v4-flash-free");
        static final List<String> NOT_ADMITTED_MIXED = List.of(
                "deepseek-v4.1-flash-free", "cat-jam-free", "nemotron-3.5-super-vl-free",
                "gpt-5.6-sol", "claude-sonnet-5", "gemini-3.6-flash", "qwen3.7-max",
                "future-unknown-model-xyz", "exo-free", "step-5-preview-free");

        private OpenCodeFreeOnlyAdmissionTestFixtures() {
        }
    }
}
