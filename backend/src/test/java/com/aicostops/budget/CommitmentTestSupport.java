package com.aicostops.budget;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixtures for the commitment lifecycle tests: a requester with
 * COMMITMENT_REQUEST, a reviewer with COMMITMENT_APPROVE + COMMITMENT_RELEASE,
 * an org-wide budget reader, a project-scoped requester/reviewer, a foreign
 * requester, an OPEN billing period, and ACTIVE scope targets.
 *
 * <p>Roles are created per-test-class (never touching the seeded catalogs),
 * grants are ORG-wide unless stated otherwise, and the authorization context
 * is always resolved fresh from MySQL by the services under test.
 */
public abstract class CommitmentTestSupport extends AuthenticationContainersSupport {

    protected static final List<String> REQUESTER_PERMISSIONS =
            List.of("COMMITMENT_REQUEST", "BUDGET_READ");
    protected static final List<String> REVIEWER_PERMISSIONS =
            List.of("COMMITMENT_REQUEST", "COMMITMENT_APPROVE", "COMMITMENT_RELEASE", "BUDGET_READ");
    protected static final List<String> READER_PERMISSIONS = List.of("BUDGET_READ");
    protected static final List<String> FOREIGN_REQUESTER_PERMISSIONS =
            List.of("COMMITMENT_REQUEST");
    protected static final List<String> SCOPED_ACTOR_PERMISSIONS =
            List.of("COMMITMENT_REQUEST", "COMMITMENT_APPROVE", "COMMITMENT_RELEASE", "BUDGET_READ");
    protected static final List<String> NONE_PERMISSIONS = List.of();

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JwtTokenService tokens;

    protected long fixtureCounter;

    protected long orgId;
    protected long foreignOrgId;
    protected long requesterUserId;
    protected long requesterMemberId;
    protected long reviewerUserId;
    protected long reviewerMemberId;
    protected long readerUserId;
    protected long readerMemberId;
    protected long scopedUserId;
    protected long scopedMemberId;
    protected long foreignRequesterUserId;
    protected long foreignRequesterMemberId;
    protected long noPermissionUserId;
    protected long noPermissionMemberId;
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
        orgId = insertOrganization("Commitment Org", "commit-" + suffix);
        foreignOrgId = insertOrganization("Commitment Foreign", "commit-foreign-" + suffix);
        requesterUserId = insertUser("commit-requester-" + suffix + "@example.com");
        requesterMemberId = insertMember(orgId, requesterUserId);
        reviewerUserId = insertUser("commit-reviewer-" + suffix + "@example.com");
        reviewerMemberId = insertMember(orgId, reviewerUserId);
        readerUserId = insertUser("commit-reader-" + suffix + "@example.com");
        readerMemberId = insertMember(orgId, readerUserId);
        scopedUserId = insertUser("commit-scoped-" + suffix + "@example.com");
        scopedMemberId = insertMember(orgId, scopedUserId);
        foreignRequesterUserId = insertUser("commit-foreign-" + suffix + "@example.com");
        foreignRequesterMemberId = insertMember(foreignOrgId, foreignRequesterUserId);
        noPermissionUserId = insertUser("commit-none-" + suffix + "@example.com");
        noPermissionMemberId = insertMember(orgId, noPermissionUserId);

        projectId = insertTarget("project", orgId, "commit-p-" + suffix);
        teamId = insertTarget("team", orgId, "commit-t-" + suffix);
        costCenterId = insertTarget("cost_center", orgId, "commit-c-" + suffix);
        periodId = insertBillingPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");

        createPermissionRole("COMMIT_REQUESTER", REQUESTER_PERMISSIONS);
        createPermissionRole("COMMIT_REVIEWER", REVIEWER_PERMISSIONS);
        createPermissionRole("COMMIT_READER", READER_PERMISSIONS);
        createPermissionRole("COMMIT_SCOPED", SCOPED_ACTOR_PERMISSIONS);
        createPermissionRole("COMMIT_FOREIGN", FOREIGN_REQUESTER_PERMISSIONS);
        createPermissionRole("COMMIT_NONE", NONE_PERMISSIONS);
        assign("COMMIT_REQUESTER", "ORG", orgId, requesterMemberId);
        assign("COMMIT_REVIEWER", "ORG", orgId, reviewerMemberId);
        assign("COMMIT_READER", "ORG", orgId, readerMemberId);
        assign("COMMIT_SCOPED", "PROJECT", projectId, scopedMemberId);
        assign("COMMIT_FOREIGN", "ORG", foreignOrgId, foreignRequesterMemberId);
        assign("COMMIT_NONE", "ORG", orgId, noPermissionMemberId);
    }

    @AfterEach
    void tearDownBase() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    // -- authentication --------------------------------------------------------

    protected AuthenticatedUser requesterUser() {
        return new AuthenticatedUser(requesterUserId, 7);
    }

    protected AuthenticatedUser reviewerUser() {
        return new AuthenticatedUser(reviewerUserId, 7);
    }

    protected AuthenticatedUser readerUser() {
        return new AuthenticatedUser(readerUserId, 7);
    }

    protected AuthenticatedUser scopedUser() {
        return new AuthenticatedUser(scopedUserId, 7);
    }

    protected AuthenticatedUser foreignRequesterUser() {
        return new AuthenticatedUser(foreignRequesterUserId, 7);
    }

    protected AuthenticatedUser noPermissionUser() {
        return new AuthenticatedUser(noPermissionUserId, 7);
    }

    protected String requesterBearer() {
        return "Bearer " + tokens.issue(requesterUserId, 7).token();
    }

    protected String reviewerBearer() {
        return "Bearer " + tokens.issue(reviewerUserId, 7).token();
    }

    protected String readerBearer() {
        return "Bearer " + tokens.issue(readerUserId, 7).token();
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

    /**
     * Inserts a commitment row directly (test setup only) with the requested
     * amount and optional approved/remaining; the caller picks the status.
     */
    protected long insertCommitmentRow(long org, long budgetId, String status,
            String requested, String approved, String remaining, long version) {
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,
                    approved_amount,remaining_amount,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, budgetId, status, requested, approved, remaining, version);
        return jdbc.queryForObject("""
                SELECT id FROM budget_commitment WHERE org_id=? AND budget_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, org, budgetId);
    }

    /** Inserts a commitment case referencing the given commitment (V12 shape). */
    protected long insertCommitmentCase(long org, long commitmentId, String status) {
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,NULL,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, commitmentId, status);
        return jdbc.queryForObject("""
                SELECT id FROM approval_case WHERE org_id=? AND budget_commitment_id=?
                """, Long.class, org, commitmentId);
    }

    protected void insertApprovalActionRow(long org, long caseId, long actorMemberId,
            String actionType, String fromState, String toState) {
        jdbc.update("""
                INSERT INTO approval_action(
                    org_id,approval_case_id,actor_member_id,action_type,
                    from_state,to_state,comment,created_at)
                VALUES (?,?,?,?,?,?,NULL,UTC_TIMESTAMP(6))
                """, org, caseId, actorMemberId, actionType, fromState, toState);
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
        var roles = List.of("COMMIT_REQUESTER", "COMMIT_REVIEWER", "COMMIT_READER",
                "COMMIT_SCOPED", "COMMIT_FOREIGN", "COMMIT_NONE");
        var inClause = String.join("','", roles);
        jdbc.update("DELETE rp FROM role_permission rp JOIN role r ON r.id=rp.role_id WHERE r.code IN ('" + inClause + "')");
        jdbc.update("DELETE FROM role WHERE code IN ('" + inClause + "')");
    }

    protected void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO role(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM role r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    protected void assign(String roleCode, String scopeType, long scopeId, long targetMemberId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM role WHERE code=?
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
                """, email, "Commitment Worker");
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

    protected String commitmentStatus(long commitmentId) {
        return jdbc.queryForObject(
                "SELECT status FROM budget_commitment WHERE id=?", String.class, commitmentId);
    }

    protected String commitmentApproved(long commitmentId) {
        return jdbc.queryForObject(
                "SELECT approved_amount FROM budget_commitment WHERE id=?", BigDecimal.class,
                commitmentId).toPlainString();
    }

    protected String commitmentRemaining(long commitmentId) {
        return jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?", BigDecimal.class,
                commitmentId).toPlainString();
    }

    protected long commitmentVersion(long commitmentId) {
        return jdbc.queryForObject(
                "SELECT version FROM budget_commitment WHERE id=?", Long.class, commitmentId);
    }

    protected String budgetCommitted(long budgetId) {
        // toPlainString: MySQL DECIMAL zeros arrive as BigDecimal(0, scale 8),
        // whose toString() uses scientific notation ("0E-8").
        return jdbc.queryForObject(
                "SELECT committed_amount FROM budget WHERE id=?", BigDecimal.class, budgetId)
                .toPlainString();
    }

    protected long budgetVersion(long budgetId) {
        return jdbc.queryForObject(
                "SELECT version FROM budget WHERE id=?", Long.class, budgetId);
    }

    protected String approvalCaseStatus(long commitmentId) {
        return jdbc.queryForObject("""
                SELECT status FROM approval_case
                WHERE org_id=? AND budget_commitment_id=?
                """, String.class, orgId, commitmentId);
    }

    protected int approvalActionCount(long commitmentId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_action aa
                JOIN approval_case ac ON ac.id=aa.approval_case_id
                WHERE ac.org_id=? AND ac.budget_commitment_id=?
                """, Integer.class, orgId, commitmentId);
    }

    protected int auditCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type=?", Integer.class, eventType);
    }
}
