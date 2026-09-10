package com.aicostops.providerhub.application;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(properties = {"aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,"aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK})
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class OpenCodeManifestIntegrationTest extends ControlPlaneFixtureSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private OpenCodeModelManifest manifest;
    private long org; private long user; private long member; private long account;
    @BeforeEach void setUp() {
        flushRedis(); cleanDatabase(); reseedCustomCatalog();
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at) VALUES ('OPENCODE_ZEN','OpenCode Zen','OPENCODE_ZEN','https://opencode.ai/zen/v1','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)");
        org = insertOrganization("Manifest Org", "manifest-org");
        user = insertUser("manifest-admin@example.com");
        member = insertMember(org, user);
        account = insertAccount(org, "OPENCODE_ZEN", "Manifest Account");
        createPermissionRole("MANI_MANAGER", List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(member, "MANI_MANAGER", "ORG", org);
        flushRedis();
    }
    @AfterEach void tearDown() { cleanDatabase(); }
    @Test void manifestVersionIsPinned() {
        assertEquals("2026-09-11-v2", manifest.version());
        assertEquals("2026-09-11-v2", OpenCodeModelManifest.MANIFEST_VERSION);
        assertEquals("2026-09-11", manifest.verifiedAt());
        assertEquals("OpenCode Zen official endpoint table", manifest.source());
        assertEquals("2026-09-11", manifest.sourceRevision());
    }
    @Test void realChatModelsAreAvailableWithChatProtocol() throws Exception {
        var conn = createOpenCodeConnection();
        manualModel(conn, "kimi-k2.6");
        assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, "kimi-k2.6"));
        assertEquals("AVAILABLE", availOf(conn, "kimi-k2.6"));
        assertTrue(manifest.isChatCompatible("kimi-k2.6"));
        manualModel(conn, "deepseek-v4-pro");
        assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, "deepseek-v4-pro"));
        assertEquals("AVAILABLE", availOf(conn, "deepseek-v4-pro"));
        assertTrue(manifest.isChatCompatible("deepseek-v4-pro"));
        manualModel(conn, "minimax-m3");
        assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, "minimax-m3"));
        assertTrue(manifest.isChatCompatible("minimax-m3"));
    }
    @Test void realFreeChatModelsCarryVerifiedFreeWithoutSuffixInference() throws Exception {
        var conn = createOpenCodeConnection();
        manualModel(conn, "big-pickle");
        assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, "big-pickle"));
        assertEquals("VERIFIED_FREE", pricingOf(conn, "big-pickle"));
        assertTrue(manifest.isChatCompatible("big-pickle"));
        manualModel(conn, "mimo-v2.5-free");
        assertEquals("OPENAI_CHAT_COMPLETIONS", protoOf(conn, "mimo-v2.5-free"));
        assertEquals("VERIFIED_FREE", pricingOf(conn, "mimo-v2.5-free"));
        assertTrue(manifest.isChatCompatible("mimo-v2.5-free"));
    }
    @Test void realUnsupportedFamiliesStayAvailableButCannotChatProbeOrPromote() throws Exception {
        var conn = createOpenCodeConnection();
        var gpt = manualModel(conn, "gpt-5.6-sol");
        assertEquals("AVAILABLE", availOf(conn, "gpt-5.6-sol"));
        assertEquals("UNSUPPORTED", protoOf(conn, "gpt-5.6-sol"));
        assertFalse(manifest.isChatCompatible("gpt-5.6-sol"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, gpt).header("Authorization", bearerFor(user))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, gpt).header("Authorization", bearerFor(user))).andExpect(status().isConflict());
        var claude = manualModel(conn, "claude-sonnet-5");
        assertEquals("UNSUPPORTED", protoOf(conn, "claude-sonnet-5"));
        assertFalse(manifest.isChatCompatible("claude-sonnet-5"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, claude).header("Authorization", bearerFor(user))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, claude).header("Authorization", bearerFor(user))).andExpect(status().isConflict());
        var qwen = manualModel(conn, "qwen3.7-max");
        assertEquals("UNSUPPORTED", protoOf(conn, "qwen3.7-max"));
        assertFalse(manifest.isChatCompatible("qwen3.7-max"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, qwen).header("Authorization", bearerFor(user))).andExpect(status().isConflict());
        var gemini = manualModel(conn, "gemini-3.6-flash");
        assertEquals("UNSUPPORTED", protoOf(conn, "gemini-3.6-flash"));
        assertFalse(manifest.isChatCompatible("gemini-3.6-flash"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, gemini).header("Authorization", bearerFor(user))).andExpect(status().isConflict());
    }
    @Test void unknownModelStaysUnknownAndCannotPromote() throws Exception {
        var conn = createOpenCodeConnection();
        var id = manualModel(conn, "future-unknown-model-xyz");
        assertEquals("UNKNOWN", protoOf(conn, "future-unknown-model-xyz"));
        assertEquals("UNKNOWN", pricingOf(conn, "future-unknown-model-xyz"));
        assertEquals("AVAILABLE", availOf(conn, "future-unknown-model-xyz"));
        assertFalse(manifest.isChatCompatible("future-unknown-model-xyz"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/probe", conn, id).header("Authorization", bearerFor(user))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, id).header("Authorization", bearerFor(user))).andExpect(status().isConflict());
    }
    @Test void freeSuffixDoesNotImplyFree() throws Exception {
        var conn = createOpenCodeConnection();
        manualModel(conn, "unknown-random-free");
        assertEquals("UNKNOWN", pricingOf(conn, "unknown-random-free"));
        assertEquals("UNKNOWN", manifest.pricingFor("unknown-random-free"));
        assertEquals("UNKNOWN", manifest.protocolFor("unknown-random-free"));
        manualModel(conn, "my-model-free");
        assertEquals("UNKNOWN", pricingOf(conn, "my-model-free"));
        assertEquals("UNKNOWN", manifest.pricingFor("my-model-free"));
    }
    @Test void verifiedFreeNeverAutoCreatesPricing() throws Exception {
        var conn = createOpenCodeConnection();
        var id = manualModel(conn, "big-pickle");
        assertEquals("VERIFIED_FREE", pricingOf(conn, "big-pickle"));
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS', verified_capabilities_json=CAST(\'{\"capabilities\":[\"CHAT_COMPLETIONS\"]}\' AS JSON) WHERE id=? AND org_id=?", id, org);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, id).header("Authorization", bearerFor(user))).andExpect(status().isCreated());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pricing_version WHERE org_id=?", Integer.class, org).intValue());
    }
    @Test void realChatModelWithSimulatedProbePromotes() throws Exception {
        var conn = createOpenCodeConnection();
        var id = manualModel(conn, "kimi-k2.6");
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS', verified_capabilities_json=CAST(\'{\"capabilities\":[\"CHAT_COMPLETIONS\"]}\' AS JSON) WHERE id=? AND org_id=?", id, org);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{d}/promote", conn, id).header("Authorization", bearerFor(user))).andExpect(status().isCreated());
    }
    @Test void productionManifestHasNoFixtureEntries() throws Exception {
        assertEquals("UNKNOWN", manifest.protocolFor("manifest-chat-fixture"));
        assertEquals("UNKNOWN", manifest.pricingFor("manifest-chat-fixture"));
        assertFalse(manifest.isChatCompatible("manifest-chat-fixture"));
        assertEquals("UNKNOWN", manifest.protocolFor("manifest-chat-free-fixture"));
        assertFalse(manifest.isChatCompatible("manifest-responses-fixture"));
        var conn = createOpenCodeConnection();
        manualModel(conn, "manifest-chat-fixture");
        assertEquals("UNKNOWN", protoOf(conn, "manifest-chat-fixture"));
        assertEquals("AVAILABLE", availOf(conn, "manifest-chat-fixture"));
    }
    @Test void testOnlyFixtureManifestIsSeparateFromProduction() {
        var fixture = OpenCodeModelManifest.testOnly(java.util.Map.of(
                "manifest-chat-fixture", new OpenCodeModelManifest.Entry(OpenCodeModelManifest.PROTOCOL_CHAT, OpenCodeModelManifest.PRICING_UNKNOWN),
                "manifest-chat-free-fixture", new OpenCodeModelManifest.Entry(OpenCodeModelManifest.PROTOCOL_CHAT, OpenCodeModelManifest.PRICING_VERIFIED_FREE),
                "manifest-responses-fixture", new OpenCodeModelManifest.Entry(OpenCodeModelManifest.PROTOCOL_UNSUPPORTED, OpenCodeModelManifest.PRICING_UNKNOWN)));
        assertTrue(fixture.isChatCompatible("manifest-chat-fixture"));
        assertFalse(manifest.isChatCompatible("manifest-chat-fixture"));
        assertEquals("OPENAI_CHAT_COMPLETIONS", fixture.protocolFor("manifest-chat-fixture"));
        assertEquals("UNKNOWN", manifest.protocolFor("manifest-chat-fixture"));
    }
    private long createOpenCodeConnection() throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections").header("Authorization", bearerFor(user)).contentType("application/json").content("{\"providerAccountId\":" + account + ",\"templateCode\":\"OPENCODE_ZEN\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
    private long manualModel(long conn, String name) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", conn).header("Authorization", bearerFor(user)).contentType("application/json").content("{\"modelName\":\"" + name + "\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
    private String protoOf(long conn, String name) {
        return jdbc.queryForObject("SELECT protocol_code FROM provider_model_discovery WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?", String.class, org, conn, name);
    }
    private String availOf(long conn, String name) {
        return jdbc.queryForObject("SELECT availability FROM provider_model_discovery WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?", String.class, org, conn, name);
    }
    private String pricingOf(long conn, String name) {
        return jdbc.queryForObject("SELECT pricing_classification FROM provider_model_discovery WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?", String.class, org, conn, name);
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
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code='MANI_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='MANI_MANAGER'");
    }
}
