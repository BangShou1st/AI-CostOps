package com.aicostops.expense;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.MinioAuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixtures for the expense workflow tests: an employee actor with the
 * OWN permission set, a finance actor with the review permission set, and
 * helpers to seed expenses directly. Mirrors the allocation test fixture
 * style. The MySQL + Redis + MinIO container set is shared so evidence
 * upload/download tests exercise the real object store.
 */
public abstract class ExpenseTestSupport extends MinioAuthenticationContainersSupport {

    protected static final List<String> EMPLOYEE_PERMISSIONS = List.of(
            "EXPENSE_CREATE_OWN", "EXPENSE_READ_OWN", "EXPENSE_SUBMIT_OWN",
            "EVIDENCE_UPLOAD_OWN");

    protected static final List<String> FINANCE_PERMISSIONS = List.of(
            "EXPENSE_REVIEW", "COST_READ",
            "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM");

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JwtTokenService tokens;

    protected long fixtureCounter;

    protected long orgId;
    protected long foreignOrgId;
    protected long employeeUserId;
    protected long employeeMemberId;
    protected long financeUserId;
    protected long financeMemberId;

    @BeforeEach
    void setUpBase() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Expense Org", "exp-" + suffix);
        foreignOrgId = insertOrganization("Expense Foreign", "exp-foreign-" + suffix);
        employeeUserId = insertUser("exp-employee-" + suffix + "@example.com");
        employeeMemberId = insertMember(orgId, employeeUserId);
        financeUserId = insertUser("exp-finance-" + suffix + "@example.com");
        financeMemberId = insertMember(orgId, financeUserId);
        createPermissionRole("EXPENSE_EMPLOYEE", EMPLOYEE_PERMISSIONS);
        createPermissionRole("EXPENSE_FINANCE", FINANCE_PERMISSIONS);
        assign("EXPENSE_EMPLOYEE", orgId, employeeMemberId);
        assign("EXPENSE_FINANCE", orgId, financeMemberId);
    }

    @AfterEach
    void tearDownBase() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    // -- authentication --------------------------------------------------------

    protected String employeeBearer() {
        return "Bearer " + tokens.issue(employeeUserId, 7).token();
    }

    protected String financeBearer() {
        return "Bearer " + tokens.issue(financeUserId, 7).token();
    }

    // -- fixtures --------------------------------------------------------------

    protected long insertExpenseDraft() {
        return insertExpenseDraftFor(orgId, employeeMemberId, "100.00000000", "CNY", "DRAFT");
    }

    protected long insertExpenseDraftFor(long org, long claimant, String amount,
            String currency, String status) {
        jdbc.update("""
                INSERT INTO expense_claim(
                    org_id,claimant_member_id,evidence_id,expense_date,amount,currency,status,
                    current_allocation_decision_id,approval_case_id,version,created_at,updated_at)
                VALUES (?,?,NULL,'2026-08-01',?,'CNY',?,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, claimant, amount, status);
        return jdbc.queryForObject(
                "SELECT id FROM expense_claim WHERE org_id=? AND claimant_member_id=? ORDER BY id DESC LIMIT 1",
                Long.class, org, claimant);
    }

    protected void setExpenseStatus(long expenseId, String status) {
        jdbc.update("UPDATE expense_claim SET status=? WHERE id=?", status, expenseId);
    }

    protected long insertEvidence(long org, long member, String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,'receipt.pdf','application/pdf',10,?,'AVAILABLE',NULL,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, member);
        return jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
    }

    protected void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('EXPENSE_EMPLOYEE','EXPENSE_FINANCE')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('EXPENSE_EMPLOYEE','EXPENSE_FINANCE')");
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

    protected void assign(String roleCode, long scopeOrgId, long targetMemberId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,'ORG',?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, targetMemberId, scopeOrgId, roleCode);
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
                """, email, "Expense Worker");
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

    protected String expenseStatus(long expenseId) {
        return jdbc.queryForObject(
                "SELECT status FROM expense_claim WHERE id=?", String.class, expenseId);
    }

    protected long expenseVersion(long expenseId) {
        return jdbc.queryForObject(
                "SELECT version FROM expense_claim WHERE id=?", Long.class, expenseId);
    }

    protected int auditCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type=?", Integer.class, eventType);
    }
}