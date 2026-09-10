package com.aicostops.providerhub.application;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.net.InetAddress;

/**
 * Shared fixtures for control-plane integration tests (M18 round-3): Testcontainers MySQL/Redis,
 * MockMvc-style org/user/role helpers, plus a lenient DNS/policy override that exists ONLY in
 * test sourcesets so controlled loopback servers are reachable. Production wiring is untouched:
 * the default beans still use system DNS with the strict public-unicast policy.
 */
public abstract class ControlPlaneFixtureSupport extends AuthenticationContainersSupport {

    public static final String JWT_SECRET = "provider-hub-test-only-signing-secret-with-more-than-32-bytes";
    public static final String TEST_KEK = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JwtTokenService tokens;
    @Autowired
    protected StringRedisTemplate redis;

    @TestConfiguration
    public static class LenientControlPlane {
        @Bean
        @Primary
        CustomEndpointValidator lenientValidator() {
            return new CustomEndpointValidator(
                    host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") }, addr -> true);
        }

        @Bean
        @Primary
        ProviderControlPlaneTransport lenientTransport() {
            return new ProviderControlPlaneTransport(
                    host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") }, addr -> true);
        }
    }

    protected void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected long insertOrganization(String name, String slug) {
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)"
                + " VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    protected long insertUser(String email) {
        jdbc.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,"
                + "created_at,updated_at) VALUES (?,'Fixture User','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    protected long insertMember(long orgId, long userId) {
        jdbc.update("INSERT INTO organization_member(org_id,user_id,status,joined_at)"
                + " VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))", orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    protected long insertAccount(long orgId, String providerCode, String displayName) {
        jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, providerCode,
                displayName);
        return jdbc.queryForObject("SELECT id FROM provider_account WHERE org_id=? AND display_name=?",
                Long.class, orgId, displayName);
    }

    protected void reseedCustomCatalog() {
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('CUSTOM_OPENAI_COMPATIBLE',"
                + "'Custom OpenAI-Compatible','CUSTOM_OPENAI_COMPATIBLE','https://example.invalid',"
                + "'DISABLED',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)");
    }

    protected void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("INSERT INTO role_permission(role_id,permission_id)"
                    + " SELECT r.id,p.id FROM `role` r JOIN permission p WHERE r.code=? AND p.code=?",
                    roleCode, permission);
        }
    }

    protected void assign(long memberId, String roleCode, String scopeType, long scopeId) {
        jdbc.update("INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)"
                + " SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?",
                memberId, scopeType, scopeId, roleCode);
    }

    protected String bearerFor(long userId) {
        return "Bearer " + tokens.issue(userId, 7).token();
    }

    protected long insertProject(long orgId, String code) {
        jdbc.update("INSERT INTO project(org_id,code,name,status,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?", Long.class, orgId,
                code);
    }

    protected long insertTeam(long orgId, String code) {
        jdbc.update("INSERT INTO team(org_id,code,name,status,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM team WHERE org_id=? AND code=?", Long.class, orgId, code);
    }

    protected long insertCostCenter(long orgId, String code) {
        jdbc.update("INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM cost_center WHERE org_id=? AND code=?", Long.class,
                orgId, code);
    }

    protected long insertGlobalLogicalModel(String modelKey) {
        jdbc.update("INSERT INTO model_catalog(model_key,name,status,capabilities_json,"
                + "default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES (?,'Fixture Model','ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", modelKey);
        return jdbc.queryForObject("SELECT id FROM model_catalog WHERE model_key=?", Long.class, modelKey);
    }

    protected long insertGlobalProviderModel(String providerCode, long logicalModelId, String wireName) {
        // Full-suite order wipes provider_catalog between classes; reseed idempotently.
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES (?, 'Custom OpenAI-Compatible', ?,"
                + "'https://example.invalid','DISABLED',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)", providerCode,
                providerCode);
        jdbc.update("INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,"
                + "routing_eligible,capabilities_json,created_at,updated_at)"
                + " VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                providerCode, logicalModelId, wireName);
        return jdbc.queryForObject(
                "SELECT id FROM provider_model WHERE provider_code=? AND provider_model_name=?",
                Long.class, providerCode, wireName);
    }
}
