package com.aicostops.iam.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=iam-mutation-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class IamMutationApiIntegrationTest extends AuthenticationContainersSupport {

    private static final long ACTOR_VERSION = 7L;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long targetUserId;
    private long targetMemberId;
    private long foreignUserId;
    private long foreignMemberId;
    private long projectId;
    private long teamId;
    private long costCenterId;
    private long foreignProjectId;
    private long foreignTeamId;
    private long foreignCostCenterId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
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

        organizationId = insertOrganization("IAM Mutation", "iam-mutation");
        foreignOrganizationId = insertOrganization("Foreign IAM Mutation", "foreign-iam-mutation");
        actorUserId = insertUser("actor@iam-mutation.test", "Mutation Admin", "ACTIVE", ACTOR_VERSION);
        actorMemberId = insertMember(organizationId, actorUserId, "ACT-1", "ACTIVE");
        insertAssignment(actorMemberId, roleId("SYSTEM_ADMIN"), "ORG", organizationId, null);
        targetUserId = insertUser("target@iam-mutation.test", "Mutation Target", "ACTIVE", 41L);
        targetMemberId = insertMember(organizationId, targetUserId, "TARGET-1", "ACTIVE");
        foreignUserId = insertUser("foreign@iam-mutation.test", "Foreign Target", "ACTIVE", 13L);
        foreignMemberId = insertMember(foreignOrganizationId, foreignUserId, "FOREIGN-1", "ACTIVE");

        projectId = insertProject(organizationId, "PROJECT-1");
        teamId = insertTeam(organizationId, "TEAM-1");
        costCenterId = insertCostCenter(organizationId, "CC-1");
        foreignProjectId = insertProject(foreignOrganizationId, "FOREIGN-PROJECT");
        foreignTeamId = insertTeam(foreignOrganizationId, "FOREIGN-TEAM");
        foreignCostCenterId = insertCostCenter(foreignOrganizationId, "FOREIGN-CC");
    }

    @Test
    void statusChangeBumpsVersionAndAudits() throws Exception {
        var oldTargetJwt = bearerFor(targetUserId, 41L);
        var disabled = patchStatus(targetUserId, "DISABLED", "41", bearer(ACTOR_VERSION))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.id").value(Long.toString(targetUserId)))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.securityVersion").value("42"))
                .andExpect(jsonPath("$.securityVersion").isString())
                .andReturn();

        assertThat(userStatus(targetUserId)).isEqualTo("DISABLED");
        assertThat(securityVersion(targetUserId)).isEqualTo(42L);
        assertUserAudit("USER_DISABLED", "ACTIVE", "DISABLED");
        assertThat(auditCount()).isEqualTo(1);
        mockMvc.perform(get("/api/v1/users").header("Authorization", oldTargetJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));

        patchStatus(targetUserId, "ACTIVE", "42", bearer(ACTOR_VERSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.securityVersion").value("43"));

        assertThat(disabled.getResponse().getContentAsString()).doesNotContain("password", "token", "secret", "jwt");
        assertThat(userStatus(targetUserId)).isEqualTo("ACTIVE");
        assertThat(securityVersion(targetUserId)).isEqualTo(43L);
        assertUserAudit("USER_ENABLED", "DISABLED", "ACTIVE");
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    void staleExpectedVersionIsConflict() throws Exception {
        patchStatus(targetUserId, "DISABLED", "40", bearer(ACTOR_VERSION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertNoTargetMutationOrAudit("ACTIVE", 41L);
    }

    @Test
    void staleSameStatusIsConflict() throws Exception {
        patchStatus(targetUserId, "ACTIVE", "40", bearer(ACTOR_VERSION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertNoTargetMutationOrAudit("ACTIVE", 41L);
    }

    @Test
    void matchingSameStatusIsNoOp() throws Exception {
        patchStatus(targetUserId, "ACTIVE", "41", bearer(ACTOR_VERSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.securityVersion").value("41"))
                .andExpect(jsonPath("$.roleAssignments").isArray());

        assertNoTargetMutationOrAudit("ACTIVE", 41L);
    }

    @Test
    void statusContractRequiresDecimalStringExpectedVersion() throws Exception {
        for (var body : List.of(
                "{\"status\":\"DISABLED\"}",
                "{\"status\":\"DISABLED\",\"expectedVersion\":41}",
                "{\"status\":\"DISABLED\",\"expectedVersion\":\"not-decimal\"}")) {
            mockMvc.perform(patch("/api/v1/users/{id}/status", targetUserId)
                            .header("Authorization", bearer(ACTOR_VERSION))
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertNoTargetMutationOrAudit("ACTIVE", 41L);
    }

    @Test
    void sensitiveMutationBypassesWarmContextCache() throws Exception {
        var assignmentId = insertAssignment(
                targetMemberId, roleId("PROJECT_OWNER"), "PROJECT", projectId, actorMemberId);
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(ACTOR_VERSION)))
                .andExpect(status().isOk());
        assertThat(redis.opsForValue().get(
                "aicostops:v1:iam:context:" + actorUserId + ":" + ACTOR_VERSION)).isNotNull();
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);

        patchStatus(targetUserId, "DISABLED", "41", bearer(ACTOR_VERSION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, roleId("EMPLOYEE"), "ORG", organizationId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId)
                        .header("Authorization", bearer(ACTOR_VERSION)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertNoTargetMutationOrAudit("ACTIVE", 41L);
        assertThat(assignmentExists(assignmentId)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roleScopeCases")
    void roleScopeMatrixControlsAssignmentCreation(
            String roleCode, String scopeType, boolean valid) throws Exception {
        var scopeId = scopeId(scopeType);
        var oldTargetJwt = bearerFor(targetUserId, 41L);
        var request = post("/api/v1/role-assignments")
                .header("Authorization", bearer(ACTOR_VERSION))
                .contentType("application/json")
                .content(roleAssignmentJson(targetMemberId, roleId(roleCode), scopeType, scopeId));

        if (!valid) {
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(assignmentCount(targetMemberId)).isZero();
            assertThat(securityVersion(targetUserId)).isEqualTo(41L);
            return;
        }

        var response = readJson(mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.role.id").isString())
                .andExpect(jsonPath("$.role.code").value(roleCode))
                .andExpect(jsonPath("$.scopeType").value(scopeType))
                .andExpect(jsonPath("$.scopeId").value(Long.toString(scopeId)))
                .andExpect(jsonPath("$.scopeId").isString())
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn());

        assertThat(assignmentCount(targetMemberId)).isEqualTo(1);
        assertThat(securityVersion(targetUserId)).isEqualTo(42L);
        assertRoleAudit("ROLE_ASSIGNED", response.path("id").asLong(), roleCode, scopeType, scopeId);
        assertThat(auditCount()).isEqualTo(1);
        mockMvc.perform(get("/api/v1/users").header("Authorization", oldTargetJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
    }

    @Test
    void organizationScopeMustUseCurrentOrganizationId() throws Exception {
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(
                                targetMemberId, roleId("EMPLOYEE"), "ORG", foreignOrganizationId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(assignmentCount(targetMemberId)).isZero();
    }

    @ParameterizedTest
    @MethodSource("foreignTypedScopes")
    void foreignTypedScopeIsNotFound(String scopeType, long ignored) throws Exception {
        var foreignScopeId = switch (scopeType) {
            case "PROJECT" -> foreignProjectId;
            case "TEAM" -> foreignTeamId;
            case "COST_CENTER" -> foreignCostCenterId;
            default -> throw new IllegalArgumentException(scopeType);
        };
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, roleId("SYSTEM_ADMIN"), scopeType, foreignScopeId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(assignmentCount(targetMemberId)).isZero();
        assertThat(securityVersion(targetUserId)).isEqualTo(41L);
    }

    @Test
    void invalidRoleAndScopeTypeAreValidationFailures() throws Exception {
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, Long.MAX_VALUE, "ORG", organizationId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, roleId("SYSTEM_ADMIN"), "PROVIDER", organizationId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void roleAssignmentContractRequiresStringIds() throws Exception {
        var employeeRoleId = roleId("EMPLOYEE");
        for (var body : List.of(
                "{\"organizationMemberId\":" + targetMemberId + ",\"roleId\":\"" + employeeRoleId
                        + "\",\"scopeType\":\"ORG\",\"scopeId\":\"" + organizationId + "\"}",
                "{\"organizationMemberId\":\"" + targetMemberId + "\",\"roleId\":" + employeeRoleId
                        + ",\"scopeType\":\"ORG\",\"scopeId\":\"" + organizationId + "\"}",
                "{\"organizationMemberId\":\"" + targetMemberId + "\",\"roleId\":\"" + employeeRoleId
                        + "\",\"scopeType\":\"ORG\",\"scopeId\":" + organizationId + "}")) {
            mockMvc.perform(post("/api/v1/role-assignments")
                            .header("Authorization", bearer(ACTOR_VERSION))
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(assignmentCount(targetMemberId)).isZero();
        assertThat(securityVersion(targetUserId)).isEqualTo(41L);
        assertThat(auditCount()).isZero();
    }

    @Test
    void inactiveAndForeignTargetsAreNotFound() throws Exception {
        jdbc.update("UPDATE organization_member SET status='DISABLED' WHERE id=?", targetMemberId);
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, roleId("EMPLOYEE"), "ORG", organizationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(foreignMemberId, roleId("EMPLOYEE"), "ORG", organizationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void disabledUserCannotReceiveRoleAssignment() throws Exception {
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?", targetUserId);

        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, roleId("EMPLOYEE"), "ORG", organizationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(userStatus(targetUserId)).isEqualTo("DISABLED");
        assertThat(assignmentCount(targetMemberId)).isZero();
        assertThat(securityVersion(targetUserId)).isEqualTo(41L);
        assertThat(auditCount()).isZero();
    }

    @Test
    void duplicateAssignmentIsConflict() throws Exception {
        var roleId = roleId("EMPLOYEE");
        insertAssignment(targetMemberId, roleId, "ORG", organizationId, actorMemberId);
        mockMvc.perform(post("/api/v1/role-assignments")
                        .header("Authorization", bearer(ACTOR_VERSION))
                        .contentType("application/json")
                        .content(roleAssignmentJson(targetMemberId, roleId, "ORG", organizationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(assignmentCount(targetMemberId)).isEqualTo(1);
        assertThat(securityVersion(targetUserId)).isEqualTo(41L);
        assertThat(auditCount()).isZero();
    }

    @Test
    void roleRevokeBumpsVersionAndAudits() throws Exception {
        var assignmentId = insertAssignment(
                targetMemberId, roleId("PROJECT_OWNER"), "PROJECT", projectId, actorMemberId);
        var oldJwt = bearerFor(targetUserId, 41L);

        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId)
                        .header("Authorization", bearer(ACTOR_VERSION)))
                .andExpect(status().isNoContent());

        assertThat(assignmentExists(assignmentId)).isFalse();
        assertThat(securityVersion(targetUserId)).isEqualTo(42L);
        assertRoleAudit("ROLE_REVOKED", assignmentId, "PROJECT_OWNER", "PROJECT", projectId);
        assertThat(auditCount()).isEqualTo(1);
        mockMvc.perform(patch("/api/v1/users/{id}/status", targetUserId)
                        .header("Authorization", oldJwt)
                        .contentType("application/json")
                        .content("{\"status\":\"ACTIVE\",\"expectedVersion\":\"42\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        mockMvc.perform(delete("/api/v1/role-assignments/{id}", assignmentId)
                        .header("Authorization", bearer(ACTOR_VERSION)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(securityVersion(targetUserId)).isEqualTo(42L);
    }

    @Test
    void crossOrganizationRoleRevokeIsNotFound() throws Exception {
        var foreignAssignmentId = insertAssignment(
                foreignMemberId, roleId("EMPLOYEE"), "ORG", foreignOrganizationId, null);
        mockMvc.perform(delete("/api/v1/role-assignments/{id}", foreignAssignmentId)
                        .header("Authorization", bearer(ACTOR_VERSION)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(assignmentExists(foreignAssignmentId)).isTrue();
        assertThat(securityVersion(foreignUserId)).isEqualTo(13L);
    }

    private static Stream<Arguments> roleScopeCases() {
        var valid = Map.of(
                "EMPLOYEE", List.of("ORG"),
                "PROJECT_OWNER", List.of("PROJECT"),
                "FINANCE_REVIEWER", List.of("ORG", "COST_CENTER"),
                "FINANCE_ADMIN", List.of("ORG"),
                "SYSTEM_ADMIN", List.of("ORG", "PROJECT", "TEAM", "COST_CENTER"));
        return List.of("EMPLOYEE", "PROJECT_OWNER", "FINANCE_REVIEWER", "FINANCE_ADMIN", "SYSTEM_ADMIN")
                .stream().flatMap(role -> List.of("ORG", "PROJECT", "TEAM", "COST_CENTER").stream()
                        .map(scope -> Arguments.of(
                                Named.of(role + " + " + scope, role), scope, valid.get(role).contains(scope))));
    }

    private static Stream<Arguments> foreignTypedScopes() {
        return Stream.of(Arguments.of("PROJECT", 0L), Arguments.of("TEAM", 0L),
                Arguments.of("COST_CENTER", 0L));
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(
            long userId, String status, String expectedVersion, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/v1/users/{id}/status", userId)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"status\":\"" + status + "\",\"expectedVersion\":\""
                        + expectedVersion + "\"}"));
    }

    private String roleAssignmentJson(long memberId, long roleId, String scopeType, long scopeId) {
        return "{\"organizationMemberId\":\"" + memberId + "\",\"roleId\":\"" + roleId
                + "\",\"scopeType\":\"" + scopeType + "\",\"scopeId\":\"" + scopeId + "\"}";
    }

    private void assertNoTargetMutationOrAudit(String status, long version) {
        assertThat(userStatus(targetUserId)).isEqualTo(status);
        assertThat(securityVersion(targetUserId)).isEqualTo(version);
        assertThat(auditCount()).isZero();
    }

    private void assertUserAudit(String eventType, String previousStatus, String newStatus) throws Exception {
        var row = audit(eventType);
        assertThat(row.organizationId()).isEqualTo(organizationId);
        assertThat(row.actorUserId()).isEqualTo(actorUserId);
        assertThat(row.subjectType()).isEqualTo("USER");
        assertThat(row.subjectId()).isEqualTo(targetUserId);
        var metadata = objectMapper.readTree(row.metadata());
        assertThat(metadata.path("previousStatus").asText()).isEqualTo(previousStatus);
        assertThat(metadata.path("newStatus").asText()).isEqualTo(newStatus);
        assertThat(metadata.path("targetMemberId").asText()).isEqualTo(Long.toString(targetMemberId));
        assertNoSecretMetadata(row.metadata());
    }

    private void assertRoleAudit(
            String eventType, long assignmentId, String roleCode, String scopeType, long scopeId) throws Exception {
        var row = audit(eventType);
        assertThat(row.organizationId()).isEqualTo(organizationId);
        assertThat(row.actorUserId()).isEqualTo(actorUserId);
        assertThat(row.subjectType()).isEqualTo("ROLE_ASSIGNMENT");
        assertThat(row.subjectId()).isEqualTo(assignmentId);
        var metadata = objectMapper.readTree(row.metadata());
        assertThat(metadata.path("targetMemberId").asText()).isEqualTo(Long.toString(targetMemberId));
        assertThat(metadata.path("roleCode").asText()).isEqualTo(roleCode);
        assertThat(metadata.path("scopeType").asText()).isEqualTo(scopeType);
        assertThat(metadata.path("scopeId").asText()).isEqualTo(Long.toString(scopeId));
        assertNoSecretMetadata(row.metadata());
    }

    private void assertNoSecretMetadata(String metadata) {
        assertThat(metadata.toLowerCase())
                .doesNotContain("password", "token", "secret", "jwt", "apikey", "api_key");
    }

    private AuditRow audit(String eventType) {
        return jdbc.queryForObject("""
                SELECT org_id,actor_user_id,subject_type,subject_id,metadata_json
                FROM audit_event WHERE event_type=? ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> new AuditRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5)), eventType);
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

    private long insertMember(long orgId, long userId, String employeeNo, String status) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,employee_no,status,joined_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, userId, employeeNo, status);
        return jdbc.queryForObject("SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, orgId, userId);
    }

    private long insertProject(long orgId, String code) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?", Long.class, orgId, code);
    }

    private long insertTeam(long orgId, String code) {
        jdbc.update("""
                INSERT INTO team(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM team WHERE org_id=? AND code=?", Long.class, orgId, code);
    }

    private long insertCostCenter(long orgId, String code) {
        jdbc.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM cost_center WHERE org_id=? AND code=?",
                Long.class, orgId, code);
    }

    private long insertAssignment(long memberId, long roleId, String scopeType, long scopeId, Long assignedBy) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))
                """, memberId, roleId, scopeType, scopeId, assignedBy);
        return jdbc.queryForObject("""
                SELECT id FROM role_assignment
                WHERE org_member_id=? AND role_id=? AND scope_type=? AND scope_id=?
                """, Long.class, memberId, roleId, scopeType, scopeId);
    }

    private long roleId(String code) {
        return jdbc.queryForObject("SELECT id FROM `role` WHERE code=?", Long.class, code);
    }

    private long scopeId(String scopeType) {
        return switch (scopeType) {
            case "ORG" -> organizationId;
            case "PROJECT" -> projectId;
            case "TEAM" -> teamId;
            case "COST_CENTER" -> costCenterId;
            default -> throw new IllegalArgumentException(scopeType);
        };
    }

    private String bearer(long securityVersion) {
        return bearerFor(actorUserId, securityVersion);
    }

    private String bearerFor(long userId, long securityVersion) {
        return "Bearer " + tokens.issue(userId, securityVersion).token();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String userStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", String.class, userId);
    }

    private long securityVersion(long userId) {
        return jdbc.queryForObject("SELECT security_version FROM app_user WHERE id=?", Long.class, userId);
    }

    private int assignmentCount(long memberId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM role_assignment WHERE org_member_id=?",
                Integer.class, memberId);
    }

    private boolean assignmentExists(long assignmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM role_assignment WHERE id=?", Integer.class, assignmentId) == 1;
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class);
    }

    private record AuditRow(
            long organizationId, long actorUserId, String subjectType, long subjectId, String metadata) {
    }
}
