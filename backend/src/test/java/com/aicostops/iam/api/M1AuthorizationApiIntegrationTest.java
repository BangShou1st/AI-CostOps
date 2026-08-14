package com.aicostops.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
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
        "aicostops.auth.jwt-signing-secret=project-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class M1AuthorizationApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long grantedProjectId;
    private long unscopedProjectId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();

        organizationId = insertOrganization();
        actorUserId = insertUser();
        actorMemberId = insertMember();
        grantedProjectId = insertProject("GRANTED", "Granted project");
        unscopedProjectId = insertProject("UNSCOPED", "Unscoped project");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void wrongRoleIsForbidden() throws Exception {
        assign("EMPLOYEE", "ORG", organizationId);

        mockMvc.perform(post("/api/v1/projects").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"code\":\"DENIED\",\"name\":\"Denied project\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void wrongScopeIsPrivacyNotFound() throws Exception {
        assign("SYSTEM_ADMIN", "PROJECT", grantedProjectId);

        mockMvc.perform(patch("/api/v1/projects/{id}", unscopedProjectId)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"name\":\"Invisible change\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail")
                        .value("The project is not available in the current organization."));
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
    }

    private long insertOrganization() {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('M1 Authorization','m1-authorization','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        return jdbc.queryForObject(
                "SELECT id FROM organization WHERE slug='m1-authorization'", Long.class);
    }

    private long insertUser() {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES ('m1-authorization@example.com','M1 Actor','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        return jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized='m1-authorization@example.com'", Long.class);
    }

    private long insertMember() {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, actorUserId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, organizationId, actorUserId);
    }

    private long insertProject(String code, String name) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, code, name);
        return jdbc.queryForObject(
                "SELECT id FROM project WHERE org_id=? AND code=?", Long.class, organizationId, code);
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
    }

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }
}
