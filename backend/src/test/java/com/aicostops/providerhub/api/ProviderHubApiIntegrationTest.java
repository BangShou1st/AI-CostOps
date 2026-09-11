package com.aicostops.providerhub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M18 Provider Hub API coverage: templates, versioning lifecycle,
 * SSRF/auth boundaries, discovery/promotion isolation and safe credential
 * projection — all through HTTP with org-scoped RBAC.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=provider-hub-test-only-signing-secret-with-more-than-32-bytes",
        "aicostops.gateway.provider-kek-v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
@AutoConfigureMockMvc
@Tag("integration")
class ProviderHubApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();
        organizationId = insertOrganization("Hub Org", "hub-org");
        foreignOrganizationId = insertOrganization("Hub Foreign", "hub-foreign");
        actorUserId = insertUser("hub-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        accountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Custom Main");
        // Other suites wipe provider_catalog between classes; reseed the
        // server-owned custom family row this test's accounts bind to.
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('CUSTOM_OPENAI_COMPATIBLE',"
                + "'Custom OpenAI-Compatible','CUSTOM_OPENAI_COMPATIBLE','https://example.invalid',"
                + "'DISABLED',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)");
        createPermissionRole("HUB_READER", List.of("PROVIDER_ACCOUNT_READ"));
        createPermissionRole("HUB_MANAGER", List.of("PROVIDER_ACCOUNT_MANAGE"));
        assign("HUB_READER", "ORG", organizationId);
        assign("HUB_MANAGER", "ORG", organizationId);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void templatesExposeServerOwnedZenDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/provider-templates").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='OPENCODE_ZEN')].baseUrl", hasItem("https://opencode.ai/zen/v1")))
                .andExpect(jsonPath("$[?(@.code=='OPENCODE_ZEN')].networkPolicy", hasItem("DIRECT_ONLY")));
    }

    @Test
    void readerCannotMutateAndSeesOnlyOwnOrg() throws Exception {
        replaceAssignment("HUB_READER", "ORG", organizationId);
        mockMvc.perform(post("/api/v1/provider-connections").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerAccountId\":1,\"baseUrl\":\"https://example.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/provider-connections").header("Authorization", bearer()))
                .andExpect(status().isOk());
        replaceAssignment("HUB_READER", "ORG", foreignOrganizationId);
        mockMvc.perform(get("/api/v1/provider-connections").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void ssrfTargetsAreRejected() throws Exception {
        for (var url : List.of("http://127.0.0.1:7897/v1", "http://10.0.0.8/v1",
                "http://169.254.169.254/", "ftp://example.com/v1")) {
            mockMvc.perform(post("/api/v1/provider-connections").header("Authorization", bearer())
                            .contentType("application/json")
                            .content("{\"providerAccountId\":" + accountId
                                    + ",\"baseUrl\":\"" + url + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ENDPOINT_BLOCKED"));
        }
        mockMvc.perform(post("/api/v1/provider-connections").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerAccountId\":" + accountId
                                + ",\"baseUrl\":\"https://example.com\",\"authType\":\"API_KEY_HEADER\","
                                + "\"authHeaderName\":\"Authorization\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void builtinTemplateFieldsAreServerOwned() throws Exception {
        mockMvc.perform(post("/api/v1/provider-connections").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerAccountId\":" + accountId
                                + ",\"templateCode\":\"OPENCODE_ZEN\","
                                + "\"baseUrl\":\"https://evil.example.test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void versionLifecycleEnforcesSingleActiveAndImmutability() throws Exception {
        var draftId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/activate", draftId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(put("/api/v1/provider-connections/{id}", draftId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"connectTimeoutMs\":3000}"))
                .andExpect(status().isConflict());
        var revisionBody = mockMvc.perform(post("/api/v1/provider-connections/{id}/revisions", draftId)
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        var revisionId = ((Number) JsonPath.read(revisionBody, "$.id")).longValue();
        mockMvc.perform(post("/api/v1/provider-connections/{id}/activate", revisionId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_connection_profile"
                + " WHERE org_id=? AND provider_account_id=? AND status='ACTIVE'",
                Integer.class, organizationId, accountId)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_connection_profile WHERE id=?",
                String.class, draftId)).isEqualTo("RETIRED");
    }

    @Test
    void discoveryPromotionIsolatesPrivateModels() throws Exception {
        var connectionId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelName\":\"model-x\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelName\":\"model-y\"}"))
                .andExpect(status().isCreated());
        var discoveryId = jdbc.queryForObject("SELECT id FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name='model-x'",
                Long.class, organizationId, connectionId);
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                + "verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                + " WHERE id=?", discoveryId);
        var promoteBody = mockMvc.perform(
                        post("/api/v1/provider-connections/{id}/models/{discoveryId}/promote",
                                connectionId, discoveryId).header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(promoteBody, "$.logicalModelId")).longValue()).isPositive();
        assertThat(((Number) JsonPath.read(promoteBody, "$.providerModelId")).longValue()).isPositive();
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM model_catalog WHERE model_key='model-x'",
                Long.class)).isEqualTo(organizationId);
    }

    @Test
    void refreshWithoutLiveIsRejectedWithZeroMutation() throws Exception {
        var connectionId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelName\":\"model-keep\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/refresh", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelNames\":[\"model-x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/refresh", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT availability FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name='model-keep'",
                String.class, organizationId, connectionId)).isEqualTo("AVAILABLE");
    }

    @Test
    void unprobedManualModelCannotPromote() throws Exception {
        var connectionId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelName\":\"model-unprobed\"}"))
                .andExpect(status().isCreated());
        var discoveryId = jdbc.queryForObject("SELECT id FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name='model-unprobed'",
                Long.class, organizationId, connectionId);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/promote",
                        connectionId, discoveryId).header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_VERIFIED"));
    }

    @Test
    void failedProbeModelCannotPromote() throws Exception {
        var connectionId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelName\":\"model-failed\"}"))
                .andExpect(status().isCreated());
        var discoveryId = jdbc.queryForObject("SELECT id FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name='model-failed'",
                Long.class, organizationId, connectionId);
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='FAIL',"
                + "verified_capabilities_json=CAST('{\"capabilities\":[]}' AS JSON),"
                + "last_probe_error_code='PROTOCOL_UNSUPPORTED' WHERE id=?", discoveryId);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/promote",
                        connectionId, discoveryId).header("Authorization", bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_VERIFIED"));
    }

    @Test
    void privateModelMustNotShadowGlobalKey() throws Exception {
        jdbc.update("INSERT INTO model_catalog(model_key,name,status,capabilities_json,"
                + "default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES ('taken-key','Taken','ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var connectionId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"modelName\":\"taken-key\"}"))
                .andExpect(status().isCreated());
        var discoveryId = jdbc.queryForObject("SELECT id FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=?",
                Long.class, organizationId, connectionId);
        jdbc.update("UPDATE provider_model_discovery SET last_probe_status='PASS',"
                + "verified_capabilities_json=CAST('{\"capabilities\":[\"CHAT_COMPLETIONS\"]}' AS JSON)"
                + " WHERE id=?", discoveryId);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/promote",
                        connectionId, discoveryId).header("Authorization", bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void credentialProjectionNeverReturnsSecrets() throws Exception {
        var connectionId = createDraft("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"https://example.com\"}");
        var created = mockMvc.perform(post("/api/v1/provider-connections/{id}/credentials", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"rawSecret\":\"sk-test-secret-12345\",\"safeLabel\":\"main\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain("sk-test-secret-12345");
        var credentialId = ((Number) JsonPath.read(created, "$.id")).longValue();
        mockMvc.perform(get("/api/v1/provider-connections/{id}/credentials", connectionId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].safeLabel").value("main"));
        mockMvc.perform(post("/api/v1/provider-connections/{id}/credentials/rotate", connectionId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"rawSecret\":\"sk-test-secret-67890\",\"safeLabel\":\"rotated\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM provider_credential WHERE id=?",
                String.class, credentialId)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_credential"
                + " WHERE org_id=? AND provider_account_id=? AND status='ACTIVE'",
                Integer.class, organizationId, accountId)).isOne();
    }

    private long createDraft(String json) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", bearer()).contentType("application/json").content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM advisor_evidence_snapshot");
        jdbc.update("DELETE FROM advisor_explanation");
        jdbc.update("DELETE FROM advisor_inference_attempt");
        jdbc.update("DELETE FROM advisor_inference_job");
        jdbc.update("DELETE FROM savings_recommendation");
        jdbc.update("DELETE FROM cost_forecast_snapshot");
        jdbc.update("DELETE FROM cost_anomaly");
        jdbc.update("DELETE FROM cost_intelligence_run");
        jdbc.update("DELETE FROM provider_model_discovery");
        jdbc.update("DELETE FROM provider_connection_profile");
        jdbc.update("UPDATE provider_credential SET predecessor_credential_id=NULL");
        jdbc.update("DELETE FROM provider_credential");
        jdbc.update("UPDATE gateway_credential SET predecessor_credential_id=NULL, advisor_profile_id=NULL");
        jdbc.update("DELETE FROM gateway_credential_model");
        jdbc.update("DELETE FROM gateway_credential");
        jdbc.update("DELETE FROM advisor_profile");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_model WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM model_catalog WHERE owner_org_id IS NOT NULL");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code LIKE 'HUB_%'");
        jdbc.update("DELETE FROM `role` WHERE code LIKE 'HUB_%'");
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)"
                + " VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,"
                + "created_at,updated_at) VALUES (?,'Hub Admin','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("INSERT INTO organization_member(org_id,user_id,status,joined_at)"
                + " VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))", orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    private long insertAccount(long orgId, String providerCode, String displayName) {
        jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, providerCode,
                displayName);
        return jdbc.queryForObject("SELECT id FROM provider_account WHERE org_id=? AND display_name=?",
                Long.class, orgId, displayName);
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("INSERT INTO role_permission(role_id,permission_id)"
                    + " SELECT r.id,p.id FROM `role` r JOIN permission p WHERE r.code=? AND p.code=?",
                    roleCode, permission);
        }
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)"
                + " SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?",
                actorMemberId, scopeType, scopeId, roleCode);
    }

    private void replaceAssignment(String roleCode, String scopeType, long scopeId) {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign(roleCode, scopeType, scopeId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }
}
