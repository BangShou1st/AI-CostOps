package com.aicostops.gatewayadmin;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared fixtures for the governed Control Plane API tests: an org-scoped
 * manager (PROVIDER_ACCOUNT_READ + PROVIDER_ACCOUNT_MANAGE), an org-scoped
 * reader, a permission-less member, a foreign-org manager, and the global
 * catalog/account/service-identity/project seed rows the lifecycle needs.
 */
public abstract class ControlPlaneApiTestSupport extends AuthenticationContainersSupport {

    /** 32 raw bytes; the credential-digest key required by GatewayKeyCodec. */
    private static final String HMAC_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @DynamicPropertySource
    static void controlPlaneProperties(DynamicPropertyRegistry registry) {
        registry.add("aicostops.gateway.credential-hmac-key-v1", () -> HMAC_KEY);
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JwtTokenService tokens;

    protected long fixtureCounter;

    protected long orgId;
    protected long foreignOrgId;
    protected long managerUserId;
    protected long managerMemberId;
    protected long readerUserId;
    protected long readerMemberId;
    protected long plainUserId;
    protected long foreignManagerUserId;
    protected long foreignManagerMemberId;

    protected long projectId;
    protected long foreignProjectId;
    protected long providerAccountId;
    protected long foreignProviderAccountId;
    protected long modelId;
    protected long providerModelId;

    @BeforeEach
    void setUpBase() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Control Plane Org", "cp-" + suffix);
        foreignOrgId = insertOrganization("Control Plane Foreign", "cp-foreign-" + suffix);
        managerUserId = insertUser("cp-manager-" + suffix + "@example.com");
        managerMemberId = insertMember(orgId, managerUserId);
        readerUserId = insertUser("cp-reader-" + suffix + "@example.com");
        readerMemberId = insertMember(orgId, readerUserId);
        plainUserId = insertUser("cp-plain-" + suffix + "@example.com");
        insertMember(orgId, plainUserId);
        foreignManagerUserId = insertUser("cp-foreign-manager-" + suffix + "@example.com");
        foreignManagerMemberId = insertMember(foreignOrgId, foreignManagerUserId);

        createPermissionRole("CP_MANAGER", List.of("PROVIDER_ACCOUNT_READ", "PROVIDER_ACCOUNT_MANAGE", "AUDIT_READ"));
        createPermissionRole("CP_READER", List.of("PROVIDER_ACCOUNT_READ"));
        createPermissionRole("CP_PLAIN", List.of("BUDGET_READ"));
        assign("CP_MANAGER", "ORG", orgId, managerMemberId);
        assign("CP_MANAGER", "ORG", foreignOrgId, foreignManagerMemberId);
        assign("CP_READER", "ORG", orgId, readerMemberId);
        assign("CP_PLAIN", "ORG", orgId, plainMemberId());

        projectId = insertProject(orgId, "cp-project-" + suffix);
        foreignProjectId = insertProject(foreignOrgId, "cp-foreign-project-" + suffix);
        modelId = insertModelCatalog("cp-model-" + suffix);
        seedProviderCatalog("TESTPC");
        providerModelId = insertProviderModel("TESTPC", modelId, "cp-mock-chat-" + suffix);
        providerAccountId = insertProviderAccount(orgId, "TESTPC", "cp-account-" + suffix);
        foreignProviderAccountId = insertProviderAccount(foreignOrgId, "TESTPC", "cp-foreign-account-" + suffix);

        flushRedis();
    }

    @AfterEach
    void tearDownBase() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    // -- authentication --------------------------------------------------------

    protected String managerBearer() {
        return "Bearer " + tokens.issue(managerUserId, 7).token();
    }

    protected String readerBearer() {
        return "Bearer " + tokens.issue(readerUserId, 7).token();
    }

    protected String plainBearer() {
        return "Bearer " + tokens.issue(plainUserId, 7).token();
    }

    protected String foreignManagerBearer() {
        return "Bearer " + tokens.issue(foreignManagerUserId, 7).token();
    }

    // -- fixtures --------------------------------------------------------------

    protected long plainMemberId() {
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, orgId, plainUserId);
    }

    protected long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    protected long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Control Plane Worker");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    protected long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }

    protected long insertProject(long org, String code) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Control Plane Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    protected long insertModelCatalog(String key) {
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                    default_max_output_tokens,max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),8192,131072,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, key, "CP Model");
        return jdbc.queryForObject("SELECT id FROM model_catalog WHERE model_key=?", Long.class, key);
    }

    protected void seedProviderCatalog(String providerCode) {
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                    capabilities_json,created_at,updated_at)
                VALUES (?,'CP Provider','TESTPC','http://mock.invalid','ACTIVE',
                    JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode);
    }

    protected long insertProviderModel(String providerCode, long model, String name) {
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                    routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,
                    JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, model, name);
        return jdbc.queryForObject(
                "SELECT id FROM provider_model WHERE provider_code=? AND model_id=?",
                Long.class, providerCode, model);
    }

    protected long insertProviderAccount(long org, String providerCode, String name) {
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,
                    status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, providerCode, name, name);
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=? AND display_name=?",
                Long.class, org, providerCode, name);
    }

    protected long insertServiceIdentity(long org, String code) {
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Control Plane Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM service_identity WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    protected void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('CP_MANAGER','CP_READER','CP_PLAIN')
                """);
        jdbc.update("""
                DELETE FROM `role`
                WHERE code IN ('CP_MANAGER','CP_READER','CP_PLAIN')
                """);
    }

    protected void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    protected void assign(String roleCode, String scopeType, long scopeId, long targetMemberId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, targetMemberId, scopeType, scopeId, roleCode);
        flushRedis();
    }

    protected void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}