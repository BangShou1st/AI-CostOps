package com.aicostops.organization.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
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

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=provider-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ProviderAccountApiIntegrationTest extends AuthenticationContainersSupport {

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
    private long activeProviderAccountId;
    private long disabledProviderAccountId;
    private long archivedProviderAccountId;
    private long foreignProviderAccountId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();

        organizationId = insertOrganization("Provider API", "provider-api");
        foreignOrganizationId = insertOrganization("Foreign", "provider-api-foreign");
        actorUserId = insertUser("provider-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        activeProviderAccountId = insertProviderAccount(
                organizationId, "AWS", "Production", "111111111111", "ACTIVE", "{\"region\":\"us-east-1\"}");
        disabledProviderAccountId = insertProviderAccount(
                organizationId, "AZURE", "Sandbox", "tenant-sandbox", "DISABLED", "{\"regions\":[\"eastus\"]}");
        archivedProviderAccountId = insertProviderAccount(
                organizationId, "GCP", "Legacy", null, "ARCHIVED", null);
        foreignProviderAccountId = insertProviderAccount(
                foreignOrganizationId, "AWS", "Foreign", "999999999999", "ACTIVE", "{\"region\":\"eu-west-1\"}");
        createPermissionRole("PROVIDER_READER", List.of("PROVIDER_ACCOUNT_READ"));
        createPermissionRole("PROVIDER_MANAGER", List.of("PROVIDER_ACCOUNT_MANAGE"));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void providerAccountRequiresOrgGrant() throws Exception {
        assign("PROVIDER_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/provider-accounts").header("Authorization", bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/provider-accounts").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerCode\":\"OCI\",\"displayName\":\"Reader cannot create\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        replaceAssignment("PROVIDER_MANAGER", "ORG", organizationId);
        mockMvc.perform(get("/api/v1/provider-accounts").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/provider-accounts").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("""
                                {"providerCode":"OCI","displayName":"Managed","metadata":{"region":"eu-frankfurt-1"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerCode").value("OCI"));
        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"displayName\":\"Managed Production\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Managed Production"));

        replaceAssignment("PROVIDER_READER", "PROJECT", 42L);
        mockMvc.perform(get("/api/v1/provider-accounts").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        replaceAssignment("PROVIDER_READER", "ORG", foreignOrganizationId);
        mockMvc.perform(get("/api/v1/provider-accounts").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void providerAccountIsCurrentOrgOnly() throws Exception {
        assign("PROVIDER_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/provider-accounts?page=0&size=2").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(
                        Long.toString(activeProviderAccountId), Long.toString(disabledProviderAccountId))))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/provider-accounts?status=ARCHIVED&page=0&size=1")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(archivedProviderAccountId)))
                .andExpect(jsonPath("$.totalElements").value(1));

        replaceAssignment("PROVIDER_MANAGER", "ORG", organizationId);
        for (var id : new long[] {foreignProviderAccountId, Long.MAX_VALUE}) {
            mockMvc.perform(patch("/api/v1/provider-accounts/{id}", id)
                            .header("Authorization", bearer()).contentType("application/json")
                            .content("{\"displayName\":\"Invisible\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail")
                            .value("The provider account is not available in the current organization."));
        }
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM provider_account WHERE id=?", String.class, foreignProviderAccountId))
                .isEqualTo("Foreign");
    }

    @Test
    void providerNaturalKeyConflict() throws Exception {
        assign("PROVIDER_MANAGER", "ORG", organizationId);

        mockMvc.perform(post("/api/v1/provider-accounts").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerCode\":\" AWS \",\"displayName\":\" Production \"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(post("/api/v1/provider-accounts").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerCode\":\"AWS\",\"displayName\":\"Development\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/provider-accounts").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"providerCode\":\"OCI\",\"displayName\":\"Production\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", disabledProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"displayName\":\"Production\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"displayName\":\"Development\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void providerCodeIsImmutable() throws Exception {
        assign("PROVIDER_MANAGER", "ORG", organizationId);

        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("""
                                {"providerCode":"GCP","displayName":"Primary","externalAccountRef":"222222222222",
                                 "metadata":{"region":"ap-southeast-2","labels":{"environment":"prod"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerCode").value("AWS"))
                .andExpect(jsonPath("$.displayName").value("Primary"))
                .andExpect(jsonPath("$.externalAccountRef").value("222222222222"))
                .andExpect(jsonPath("$.metadata.region").value("ap-southeast-2"))
                .andExpect(jsonPath("$.metadata.labels.environment").value("prod"));

        var row = jdbc.queryForMap("""
                SELECT provider_code,display_name,external_account_ref,
                       JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.labels.environment')) environment
                FROM provider_account WHERE id=?
                """, activeProviderAccountId);
        assertThat(row)
                .containsEntry("provider_code", "AWS")
                .containsEntry("display_name", "Primary")
                .containsEntry("external_account_ref", "222222222222")
                .containsEntry("environment", "prod");
    }

    @Test
    void providerMetadataRejectsSecretKeys() throws Exception {
        assign("PROVIDER_MANAGER", "ORG", organizationId);

        for (var secretKey : List.of(
                "password", "dbPasswordHash", "access-token", "refresh_token", "client.secret", "api_key", "myApiKeyValue")) {
            var body = """
                    {"providerCode":"OCI","displayName":"Rejected","metadata":{"safe":[{"%s":"never-store"}]}}
                    """.formatted(secretKey);
            mockMvc.perform(post("/api/v1/provider-accounts").header("Authorization", bearer())
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_account WHERE org_id=? AND provider_code='OCI'",
                Integer.class, organizationId)).isZero();

        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"metadata\":{\"nested\":{\"API-Key\":\"never-store\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.region')) FROM provider_account WHERE id=?",
                String.class, activeProviderAccountId)).isEqualTo("us-east-1");
    }

    @Test
    void providerLifecyclePreservesRow() throws Exception {
        assign("PROVIDER_MANAGER", "ORG", organizationId);

        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(patch("/api/v1/provider-accounts/{id}", activeProviderAccountId)
                        .header("Authorization", bearer()).contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_account WHERE id=? AND status='ARCHIVED'",
                Integer.class, activeProviderAccountId)).isEqualTo(1);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        // M6 close/reconciliation history references organization members.
        jdbc.update("DELETE FROM period_close_check");
        jdbc.update("DELETE FROM period_close_run");
        jdbc.update("DELETE FROM reconciliation_case");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code LIKE 'PROVIDER_%'");
        jdbc.update("DELETE FROM `role` WHERE code LIKE 'PROVIDER_%'");
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,'Provider Admin','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    private long insertProviderAccount(
            long orgId, String providerCode, String displayName, String externalAccountRef,
            String status, String metadataJson) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,?,CAST(? AS JSON),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, displayName, externalAccountRef, status, metadataJson);
        return jdbc.queryForObject("""
                SELECT id FROM provider_account
                WHERE org_id=? AND provider_code=? AND display_name=?
                """, Long.class, orgId, providerCode, displayName);
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
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
