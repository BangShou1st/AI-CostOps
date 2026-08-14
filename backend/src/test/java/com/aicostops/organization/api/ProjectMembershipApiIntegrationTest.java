package com.aicostops.organization.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "aicostops.auth.jwt-signing-secret=project-membership-test-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ProjectMembershipApiIntegrationTest extends AuthenticationContainersSupport {

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
    private long firstUserId;
    private long firstMemberId;
    private long secondUserId;
    private long secondMemberId;
    private long inactiveMemberId;
    private long foreignMemberId;
    private long activeProjectId;
    private long disabledProjectId;
    private long foreignProjectId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();

        organizationId = insertOrganization("Project Membership", "project-membership");
        foreignOrganizationId = insertOrganization("Foreign Membership", "foreign-membership");
        actorUserId = insertUser("membership-admin@example.com", "Membership Admin", "ACTIVE", 7);
        actorMemberId = insertMember(organizationId, actorUserId, "ACTIVE");
        firstUserId = insertUser("first-member@example.com", "First Member", "ACTIVE", 7);
        firstMemberId = insertMember(organizationId, firstUserId, "ACTIVE");
        secondUserId = insertUser("second-member@example.com", "Second Member", "ACTIVE", 11);
        secondMemberId = insertMember(organizationId, secondUserId, "ACTIVE");
        var inactiveUserId = insertUser("inactive-member@example.com", "Inactive Member", "ACTIVE", 3);
        inactiveMemberId = insertMember(organizationId, inactiveUserId, "DISABLED");
        var foreignUserId = insertUser("foreign-member@example.com", "Foreign Member", "ACTIVE", 5);
        foreignMemberId = insertMember(foreignOrganizationId, foreignUserId, "ACTIVE");
        activeProjectId = insertProject(organizationId, "ACTIVE", "Active Project", "ACTIVE");
        disabledProjectId = insertProject(organizationId, "DISABLED", "Disabled Project", "DISABLED");
        foreignProjectId = insertProject(foreignOrganizationId, "FOREIGN", "Foreign Project", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void listsProjectMembersWithStablePageCount() throws Exception {
        assign(permissionRole("PROJECT_READ"), "PROJECT", activeProjectId);
        var firstProjectMemberId = insertProjectMember(activeProjectId, firstMemberId, "ACTIVE");
        insertProjectMember(activeProjectId, secondMemberId, "ACTIVE");
        insertProjectMember(activeProjectId, actorMemberId, "DISABLED");

        mockMvc.perform(get("/api/v1/projects/{id}/members?status=ACTIVE&page=0&size=1", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(firstProjectMemberId)))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].organizationMemberId").value(Long.toString(firstMemberId)))
                .andExpect(jsonPath("$.items[0].userId").value(Long.toString(firstUserId)))
                .andExpect(jsonPath("$.items[0].email").value("first-member@example.com"))
                .andExpect(jsonPath("$.items[0].displayName").value("First Member"))
                .andExpect(jsonPath("$.items[0].userStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].joinedAt").isString())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        clearAssignments();
        assign(permissionRole("PROJECT_MEMBER_MANAGE"), "ORG", organizationId);
        mockMvc.perform(get("/api/v1/projects/{id}/members?status=ACTIVE", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].organizationMemberId").value(containsInAnyOrder(
                        Long.toString(firstMemberId), Long.toString(secondMemberId))))
                .andExpect(jsonPath("$.totalElements").value(2));

        clearAssignments();
        assign(permissionRole("PROJECT_READ"), "PROJECT", activeProjectId);
        mockMvc.perform(post("/api/v1/projects/{id}/members", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7))
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + actorMemberId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossOrgProjectMemberIsNotFound() throws Exception {
        assign(permissionRole("PROJECT_MEMBER_MANAGE"), "ORG", organizationId);
        var foreignProjectMemberId = insertProjectMember(foreignProjectId, foreignMemberId, "ACTIVE");

        for (var request : java.util.List.of(
                post("/api/v1/projects/{id}/members", activeProjectId)
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + foreignMemberId + "\"}"),
                post("/api/v1/projects/{id}/members", foreignProjectId)
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + firstMemberId + "\"}"),
                post("/api/v1/projects/{id}/members", disabledProjectId)
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + firstMemberId + "\"}"),
                post("/api/v1/projects/{id}/members", activeProjectId)
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + inactiveMemberId + "\"}"),
                delete("/api/v1/projects/{id}/members/{memberId}", activeProjectId, foreignProjectMemberId))) {
            mockMvc.perform(request.header("Authorization", bearer(actorUserId, 7)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_member WHERE project_id=?", Integer.class, activeProjectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }

    @Test
    void addReactivatesExistingMembership() throws Exception {
        assign(permissionRole("PROJECT_MEMBER_MANAGE"), "ORG", organizationId);
        var existingId = insertProjectMember(activeProjectId, firstMemberId, "DISABLED");
        insertProjectMember(activeProjectId, secondMemberId, "ARCHIVED");

        mockMvc.perform(post("/api/v1/projects/{id}/members", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7))
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + firstMemberId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(Long.toString(existingId)))
                .andExpect(jsonPath("$.organizationMemberId").value(Long.toString(firstMemberId)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_member WHERE project_id=? AND org_member_id=?",
                Integer.class, activeProjectId, firstMemberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM project_member WHERE id=?", String.class, existingId))
                .isEqualTo("ACTIVE");

        var versionAfterReactivation = securityVersion(firstUserId);
        var auditsAfterReactivation = auditCount();
        mockMvc.perform(post("/api/v1/projects/{id}/members", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7))
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + firstMemberId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(securityVersion(firstUserId)).isEqualTo(versionAfterReactivation);
        assertThat(auditCount()).isEqualTo(auditsAfterReactivation);

        var archivedVersion = securityVersion(secondUserId);
        mockMvc.perform(post("/api/v1/projects/{id}/members", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7))
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + secondMemberId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM project_member WHERE project_id=? AND org_member_id=?",
                String.class, activeProjectId, secondMemberId)).isEqualTo("ARCHIVED");
        assertThat(securityVersion(secondUserId)).isEqualTo(archivedVersion);
        assertThat(auditCount()).isEqualTo(auditsAfterReactivation);
    }

    @Test
    void deleteDisablesWithoutDeleting() throws Exception {
        assign(permissionRole("PROJECT_MEMBER_MANAGE"), "PROJECT", activeProjectId);
        var projectMemberId = insertProjectMember(activeProjectId, secondMemberId, "ACTIVE");

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{memberId}", activeProjectId, projectMemberId)
                        .header("Authorization", bearer(actorUserId, 7)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_member WHERE id=?", Integer.class,
                projectMemberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM project_member WHERE id=?", String.class,
                projectMemberId)).isEqualTo("DISABLED");
        assertThat(securityVersion(secondUserId)).isEqualTo(12);

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{memberId}", activeProjectId, projectMemberId)
                        .header("Authorization", bearer(actorUserId, 7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(securityVersion(secondUserId)).isEqualTo(12);
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void projectMembershipChangeBumpsVersionAndAudits() throws Exception {
        assign(permissionRole("PROJECT_MEMBER_MANAGE"), "ORG", organizationId);
        var oldTargetBearer = bearer(firstUserId, 7);

        var result = mockMvc.perform(post("/api/v1/projects/{id}/members", activeProjectId)
                        .header("Authorization", bearer(actorUserId, 7))
                        .contentType("application/json")
                        .content("{\"organizationMemberId\":\"" + firstMemberId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var membershipId = result.getResponse().getContentAsString()
                .replaceFirst(".*\"id\":\"([0-9]+)\".*", "$1");

        assertThat(securityVersion(firstUserId)).isEqualTo(8);
        var audit = jdbc.queryForMap("""
                SELECT org_id,actor_user_id,event_type,subject_type,subject_id,metadata_json
                FROM audit_event
                """);
        assertThat(audit.get("org_id")).isEqualTo(organizationId);
        assertThat(audit.get("actor_user_id")).isEqualTo(actorUserId);
        assertThat(audit.get("event_type")).isEqualTo("MEMBERSHIP_CHANGED");
        assertThat(audit.get("subject_type")).isEqualTo("PROJECT_MEMBER");
        assertThat(audit.get("subject_id").toString()).isEqualTo(membershipId);
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.parentType')) FROM audit_event", String.class))
                .isEqualTo("PROJECT");
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.parentId')) FROM audit_event", String.class))
                .isEqualTo(Long.toString(activeProjectId));
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.memberId')) FROM audit_event", String.class))
                .isEqualTo(membershipId);
        assertThat(jdbc.queryForObject(
                "SELECT JSON_EXTRACT(metadata_json,'$.previousStatus') IS NULL FROM audit_event", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT JSON_TYPE(JSON_EXTRACT(metadata_json,'$.previousStatus')) FROM audit_event", String.class))
                .isEqualTo("NULL");
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.newStatus')) FROM audit_event", String.class))
                .isEqualTo("ACTIVE");

        mockMvc.perform(get("/api/v1/projects/{id}/members", activeProjectId)
                        .header("Authorization", oldTargetBearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code LIKE 'TASK9_%'");
        jdbc.update("DELETE FROM `role` WHERE code LIKE 'TASK9_%'");
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email, String displayName, String status, long securityVersion) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, displayName, status, securityVersion);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId, String status) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,?,UTC_TIMESTAMP(6))
                """, orgId, userId, status);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    private long insertProject(long orgId, String code, String name, String status) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, code, name, status);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?", Long.class, orgId, code);
    }

    private long insertProjectMember(long projectId, long organizationMemberId, String status) {
        jdbc.update("""
                INSERT INTO project_member(project_id,org_member_id,status,joined_at)
                VALUES (?,?,?,UTC_TIMESTAMP(6))
                """, projectId, organizationMemberId, status);
        return jdbc.queryForObject("SELECT id FROM project_member WHERE project_id=? AND org_member_id=?",
                Long.class, projectId, organizationMemberId);
    }

    private String permissionRole(String permission) {
        var roleCode = "TASK9_" + permission;
        jdbc.update("INSERT IGNORE INTO `role`(code,name) VALUES (?,?)", roleCode, "Task 9 " + permission);
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code=? AND p.code=?
                """, roleCode, permission);
        return roleCode;
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
        flushAuthorizationCache();
    }

    private void clearAssignments() {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        flushAuthorizationCache();
    }

    private long securityVersion(long userId) {
        return jdbc.queryForObject("SELECT security_version FROM app_user WHERE id=?", Long.class, userId);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
    }

    private void flushAuthorizationCache() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private String bearer(long userId, long securityVersion) {
        return "Bearer " + tokens.issue(userId, securityVersion).token();
    }
}
