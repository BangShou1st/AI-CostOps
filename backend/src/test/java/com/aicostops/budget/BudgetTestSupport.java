package com.aicostops.budget;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixtures for the budget management tests: a BUDGET_MANAGE actor,
 * an org-wide BUDGET_READ actor, a project-scoped BUDGET_READ actor, a
 * foreign-org reader, an OPEN billing period, and ACTIVE scope targets.
 */
public abstract class BudgetTestSupport extends AuthenticationContainersSupport {

    protected static final List<String> MANAGER_PERMISSIONS = List.of("BUDGET_READ", "BUDGET_MANAGE");
    protected static final List<String> READER_PERMISSIONS = List.of("BUDGET_READ");
    protected static final List<String> FOREIGN_READER_PERMISSIONS = List.of("BUDGET_READ");

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
    protected long projectOwnerUserId;
    protected long projectOwnerMemberId;
    protected long foreignReaderUserId;
    protected long foreignReaderMemberId;
    protected long projectId;
    protected long teamId;
    protected long costCenterId;
    protected long periodId;

    @BeforeEach
    void setUpBase() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Budget Org", "budget-" + suffix);
        foreignOrgId = insertOrganization("Budget Foreign", "budget-foreign-" + suffix);
        managerUserId = insertUser("budget-manager-" + suffix + "@example.com");
        managerMemberId = insertMember(orgId, managerUserId);
        readerUserId = insertUser("budget-reader-" + suffix + "@example.com");
        readerMemberId = insertMember(orgId, readerUserId);
        projectOwnerUserId = insertUser("budget-owner-" + suffix + "@example.com");
        projectOwnerMemberId = insertMember(orgId, projectOwnerUserId);
        foreignReaderUserId = insertUser("budget-foreign-reader-" + suffix + "@example.com");
        foreignReaderMemberId = insertMember(foreignOrgId, foreignReaderUserId);

        projectId = insertTarget("project", orgId, "budget-p-" + suffix);
        teamId = insertTarget("team", orgId, "budget-t-" + suffix);
        costCenterId = insertTarget("cost_center", orgId, "budget-c-" + suffix);
        periodId = insertBillingPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");

        createPermissionRole("BUDGET_MANAGER", MANAGER_PERMISSIONS);
        createPermissionRole("BUDGET_READER", READER_PERMISSIONS);
        createPermissionRole("BUDGET_PROJECT_OWNER", READER_PERMISSIONS);
        createPermissionRole("BUDGET_FOREIGN_READER", FOREIGN_READER_PERMISSIONS);
        assign("BUDGET_MANAGER", "ORG", orgId, managerMemberId);
        assign("BUDGET_READER", "ORG", orgId, readerMemberId);
        assign("BUDGET_PROJECT_OWNER", "PROJECT", projectId, projectOwnerMemberId);
        assign("BUDGET_FOREIGN_READER", "ORG", foreignOrgId, foreignReaderMemberId);
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

    protected String projectOwnerBearer() {
        return "Bearer " + tokens.issue(projectOwnerUserId, 7).token();
    }

    protected String foreignReaderBearer() {
        return "Bearer " + tokens.issue(foreignReaderUserId, 7).token();
    }

    // -- fixtures --------------------------------------------------------------

    protected long insertBillingPeriod(long org, String start, String end, String status) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,?,?,?,0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, start, end, status);
        return jdbc.queryForObject("""
                SELECT id FROM billing_period
                WHERE org_id=? AND period_start=? AND period_end=?
                """, Long.class, org, start, end);
    }

    protected long insertBudgetRow(long org, long period, String scopeType, long scopeId,
            String currency, String total, String actual, String committed) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, period, scopeType, scopeId, currency, total, actual, committed);
        return jdbc.queryForObject("""
                SELECT id FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type=? AND scope_id=? AND currency=?
                """, Long.class, org, period, scopeType, scopeId, currency);
    }

    protected long insertTarget(String table, long org, String code) {
        jdbc.update("""
                INSERT INTO %s(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Target','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """.formatted(table), org, code);
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE org_id=? AND code=?", Long.class, org, code);
    }

    protected void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('BUDGET_MANAGER','BUDGET_READER','BUDGET_PROJECT_OWNER','BUDGET_FOREIGN_READER')
                """);
        jdbc.update("""
                DELETE FROM `role`
                WHERE code IN ('BUDGET_MANAGER','BUDGET_READER','BUDGET_PROJECT_OWNER','BUDGET_FOREIGN_READER')
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
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
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
                """, email, "Budget Worker");
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

    // -- assertions helpers ----------------------------------------------------

    protected long budgetVersion(long budgetId) {
        return jdbc.queryForObject(
                "SELECT version FROM budget WHERE id=?", Long.class, budgetId);
    }

    protected int auditCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type=?", Integer.class, eventType);
    }

    protected long securityVersion(long userId) {
        return jdbc.queryForObject(
                "SELECT security_version FROM app_user WHERE id=?", Long.class, userId);
    }
}