package com.aicostops.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.stream.StreamSupport;
import java.util.Set;
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
        "aicostops.auth.jwt-signing-secret=iam-read-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class IamReadApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long representedUserId;
    private long foreignUserId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();

        organizationId = insertOrganization("IAM Read", "iam-read");
        var foreignOrganizationId = insertOrganization("Foreign", "iam-read-foreign");
        actorUserId = insertUser("actor@iam-read.test", "IAM Reader", 7);
        actorMemberId = insertMember(organizationId, actorUserId, "ACT-1", null);
        assign(actorMemberId, "SYSTEM_ADMIN", "ORG", organizationId);

        jdbcTemplate.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,'ENG','Engineering','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId);
        var costCenterId = jdbcTemplate.queryForObject(
                "SELECT id FROM cost_center WHERE org_id=? AND code='ENG'", Long.class, organizationId);

        representedUserId = insertUser("member@iam-read.test", "Represented Member", 9007199254740993L);
        var representedMemberId = insertMember(organizationId, representedUserId, "EMP-9", costCenterId);
        assign(representedMemberId, "EMPLOYEE", "ORG", organizationId);

        foreignUserId = insertUser("foreign@iam-read.test", "Foreign Member", 11);
        insertMember(foreignOrganizationId, foreignUserId, "OUT-1", null);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM audit_event");
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM cost_center");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM organization");
    }

    @Test
    void usersListAndDetailShareFullRepresentation() throws Exception {
        var list = readJson(mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andReturn().getResponse().getContentAsString());
        var listed = StreamSupport.stream(list.path("items").spliterator(), false)
                .filter(item -> item.path("id").asText().equals(Long.toString(representedUserId)))
                .findFirst().orElseThrow();

        var detail = readJson(mockMvc.perform(get("/api/v1/users/{id}", representedUserId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(detail).isEqualTo(listed);
        assertThat(detail.path("id").asText()).isEqualTo(Long.toString(representedUserId));
        assertThat(detail.path("email").asText()).isEqualTo("member@iam-read.test");
        assertThat(detail.path("displayName").asText()).isEqualTo("Represented Member");
        assertThat(detail.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.path("securityVersion").asText()).isEqualTo("9007199254740993");
        assertThat(detail.path("securityVersion").isTextual()).isTrue();
        assertThat(detail.path("organizationMember").path("employeeNo").asText()).isEqualTo("EMP-9");
        assertThat(detail.path("organizationMember").path("defaultCostCenterId").isTextual()).isTrue();
        assertThat(detail.path("roleAssignments").get(0).path("id").isTextual()).isTrue();
        assertThat(detail.path("roleAssignments").get(0).path("role").path("code").asText())
                .isEqualTo("EMPLOYEE");
        assertThat(detail.path("roleAssignments").get(0).path("role").propertyNames())
                .containsExactlyInAnyOrder("id", "code", "name");
        assertThat(detail.path("roleAssignments").get(0).path("scopeType").asText()).isEqualTo("ORG");
        assertThat(detail.path("roleAssignments").get(0).path("scopeId").asText())
                .isEqualTo(Long.toString(organizationId));
        assertThat(detail.path("roleAssignments").get(0).path("createdAt").isTextual()).isTrue();
    }

    @Test
    void usersAreCurrentOrgOnly() throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.hasItems(
                        Long.toString(actorUserId), Long.toString(representedUserId))))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(Long.toString(foreignUserId)))));
    }

    @Test
    void usersCountMatchesRowPredicate() throws Exception {
        mockMvc.perform(get("/api/v1/users?page=0&size=1").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void rolesAndPermissionsAreUnpaged() throws Exception {
        var roles = readJson(mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var permissions = readJson(mockMvc.perform(get("/api/v1/permissions").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(roles.isArray()).isTrue();
        assertThat(roles).hasSize(5);
        assertThat(roles.get(0).path("id").isTextual()).isTrue();
        var systemAdmin = StreamSupport.stream(roles.spliterator(), false)
                .filter(role -> role.path("code").asText().equals("SYSTEM_ADMIN"))
                .findFirst().orElseThrow();
        assertThat(systemAdmin.path("name").asText()).isEqualTo("System Admin");
        assertThat(systemAdmin.path("permissions").isArray()).isTrue();
        assertThat(StreamSupport.stream(systemAdmin.path("permissions").spliterator(), false)
                .map(permission -> permission.path("code").asText())
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(
                        "USER_READ", "USER_MANAGE", "USER_INVITE", "ROLE_READ", "ROLE_ASSIGN",
                        "PROJECT_READ", "PROJECT_MANAGE", "PROJECT_MEMBER_MANAGE", "TEAM_READ", "TEAM_MANAGE",
                        "COST_CENTER_READ", "COST_CENTER_MANAGE", "PROVIDER_ACCOUNT_READ",
                        "PROVIDER_ACCOUNT_MANAGE", "AUDIT_READ"));
        assertThat(StreamSupport.stream(systemAdmin.path("permissions").spliterator(), false)
                .allMatch(permission -> new java.util.HashSet<>(permission.propertyNames())
                        .equals(Set.of("id", "code", "name"))
                        && permission.path("id").isTextual()
                        && permission.path("name").isTextual()))
                .isTrue();
        var userRead = StreamSupport.stream(systemAdmin.path("permissions").spliterator(), false)
                .filter(permission -> permission.path("code").asText().equals("USER_READ"))
                .findFirst().orElseThrow();
        assertThat(userRead.path("id").asText()).isEqualTo("1");
        assertThat(userRead.path("name").asText()).isEqualTo("Read users");
        assertThat(permissions.isArray()).isTrue();
        assertThat(permissions).hasSize(48);
        assertThat(permissions.get(0).path("id").isTextual()).isTrue();
        assertThat(permissions.toString()).contains("USER_READ", "Read users");
    }

    @Test
    void missingReadPermissionIsForbidden() throws Exception {
        jdbcTemplate.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign(actorMemberId, "EMPLOYEE", "ORG", organizationId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/roles").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/permissions").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossOrganizationUserIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", foreignUserId).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/users/{id}", Long.MAX_VALUE).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private long insertOrganization(String name, String slug) {
        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbcTemplate.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email, String displayName, long securityVersion) {
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, displayName, securityVersion);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId, String employeeNo, Long defaultCostCenterId) {
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,employee_no,default_cost_center_id,status,joined_at)
                VALUES (?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId, employeeNo, defaultCostCenterId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    private void assign(long memberId, String roleCode, String scopeType, long scopeId) {
        jdbcTemplate.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, memberId, scopeType, scopeId, roleCode);
    }

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }

    private JsonNode readJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
