package com.aicostops.iam.api;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.application.SecurityVersionService;
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
        "aicostops.auth.jwt-signing-secret=me-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class MeApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SecurityVersionService securityVersions;

    private long organizationId;
    private long userId;
    private long memberId;
    private long projectId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        cleanDatabase();

        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('Me API','me-api','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        organizationId = jdbc.queryForObject(
                "SELECT id FROM organization WHERE slug='me-api'", Long.class);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES ('me-api@example.com','Me API User','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized='me-api@example.com'", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, userId);
        memberId = jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, organizationId, userId);
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,'ME-PROJECT','Me Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId);
        projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE org_id=? AND code='ME-PROJECT'", Long.class, organizationId);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void meProjectsOnlyApplicableAdminPermissions() throws Exception {
        assign("SYSTEM_ADMIN", "PROJECT", projectId);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(userId)))
                .andExpect(jsonPath("$.email").value("me-api@example.com"))
                .andExpect(jsonPath("$.displayName").value("Me API User"))
                .andExpect(jsonPath("$.organizationId").value(Long.toString(organizationId)))
                .andExpect(jsonPath("$.organizationMemberId").value(Long.toString(memberId)))
                .andExpect(jsonPath("$.permissions", contains(
                        "PROJECT_MANAGE", "PROJECT_MEMBER_MANAGE", "PROJECT_READ")));
    }

    @Test
    void meProjectsAllFinancePermissionsIncludingM3() throws Exception {
        assign("FINANCE_ADMIN", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", contains(
                        "ALLOCATION_CONFIRM",
                        "ALLOCATION_EDIT",
                        "ALLOCATION_READ",
                        "ALLOCATION_RULE_MANAGE",
                        "AUDIT_READ",
                        "BUDGET_MANAGE",
                        "BUDGET_READ",
                        "COMMITMENT_APPROVE",
                        "COMMITMENT_RELEASE",
                        "COMMITMENT_REQUEST",
                        "COST_READ",
                        "DUPLICATE_REVIEW",
                        "EVIDENCE_DOWNLOAD",
                        "EVIDENCE_READ",
                        "EVIDENCE_UPLOAD_PROVIDER",
                        "EXPENSE_POST",
                        "EXPENSE_REVIEW",
                        "IMPORT_CANCEL",
                        "IMPORT_CONFIRM",
                        "IMPORT_READ",
                        "IMPORT_RETRY",
                        "LEDGER_CORRECT",
                        "LEDGER_POST",
                        "LEDGER_READ",
                        "PERIOD_CLOSE",
                        "PERIOD_READ",
                        "PERIOD_REOPEN",
                        "PROVIDER_ACCOUNT_MANAGE",
                        "PROVIDER_ACCOUNT_READ",
                        "RECONCILIATION_READ",
                        "RECONCILIATION_RESOLVE",
                        "RECONCILIATION_RUN")));
    }

    @Test
    void mePermissionsAreSortedAndDeduplicated() throws Exception {
        assign("SYSTEM_ADMIN", "ORG", organizationId);
        assign("SYSTEM_ADMIN", "PROJECT", projectId);
        assign("FINANCE_ADMIN", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", contains(
                        "ALLOCATION_CONFIRM",
                        "ALLOCATION_EDIT",
                        "ALLOCATION_READ",
                        "ALLOCATION_RULE_MANAGE",
                        "AUDIT_READ",
                        "BUDGET_MANAGE",
                        "BUDGET_READ",
                        "COMMITMENT_APPROVE",
                        "COMMITMENT_RELEASE",
                        "COMMITMENT_REQUEST",
                        "COST_CENTER_MANAGE",
                        "COST_CENTER_READ",
                        "COST_READ",
                        "DUPLICATE_REVIEW",
                        "EVIDENCE_DOWNLOAD",
                        "EVIDENCE_READ",
                        "EVIDENCE_UPLOAD_PROVIDER",
                        "EXPENSE_POST",
                        "EXPENSE_REVIEW",
                        "IMPORT_CANCEL",
                        "IMPORT_CONFIRM",
                        "IMPORT_READ",
                        "IMPORT_RETRY",
                        "LEDGER_CORRECT",
                        "LEDGER_POST",
                        "LEDGER_READ",
                        "PERIOD_CLOSE",
                        "PERIOD_READ",
                        "PERIOD_REOPEN",
                        "PROJECT_MANAGE",
                        "PROJECT_MEMBER_MANAGE",
                        "PROJECT_READ",
                        "PROVIDER_ACCOUNT_MANAGE",
                        "PROVIDER_ACCOUNT_READ",
                        "RECONCILIATION_READ",
                        "RECONCILIATION_RESOLVE",
                        "RECONCILIATION_RUN",
                        "ROLE_ASSIGN",
                        "ROLE_READ",
                        "TEAM_MANAGE",
                        "TEAM_READ",
                        "USER_INVITE",
                        "USER_MANAGE",
                        "USER_READ")))
                .andExpect(jsonPath("$.scopes").doesNotExist())
                .andExpect(jsonPath("$.scopeIds").doesNotExist())
                .andExpect(jsonPath("$.roleCodes").doesNotExist());
    }

    @Test
    void staleMeContextIsUnauthorized() throws Exception {
        assign("SYSTEM_ADMIN", "ORG", organizationId);
        securityVersions.current(userId);
        jdbc.update("UPDATE app_user SET security_version=8 WHERE id=?", userId);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
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
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, memberId, scopeType, scopeId, roleCode);
    }

    private String bearer() {
        return "Bearer " + tokens.issue(userId, 7).token();
    }
}
