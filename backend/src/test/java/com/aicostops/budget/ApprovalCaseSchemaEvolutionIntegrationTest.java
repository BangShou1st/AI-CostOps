package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V12 approval_case schema evolution contract: one approval case must bind
 * exactly one subject — an EXPENSE_CLAIM or a BUDGET_COMMITMENT — enforced by
 * real MySQL constraints (nullable same-org composite FKs + XOR CHECK), the
 * expense integrity of V10 is fully preserved, and one commitment cannot get
 * a second approval case.
 *
 * <p>Everything here is verified with direct JDBC against the real MySQL
 * Testcontainer: constraint rejections must come from MySQL itself, never
 * from application code.
 */
@SpringBootTest
@Tag("integration")
class ApprovalCaseSchemaEvolutionIntegrationTest extends MySqlContainerSupport {

    private static final String AUG_1 = "2026-08-01 00:00:00.000000";
    private static final String SEP_1 = "2026-09-01 00:00:00.000000";

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong fixture = new AtomicLong();

    private long orgId;
    private long foreignOrgId;
    private long memberId;
    private long foreignMemberId;
    private long claimId;
    private long foreignClaimId;
    private long commitmentId;
    private long foreignCommitmentId;

    @BeforeEach
    void setUp() {
        var suffix = fixture.incrementAndGet() + "-" + System.nanoTime();
        orgId = insertOrganization("Case Org " + suffix, "case-" + suffix);
        foreignOrgId = insertOrganization("Case Foreign " + suffix, "case-foreign-" + suffix);
        memberId = insertMember(orgId, insertUser("case-" + suffix + "@example.com"));
        foreignMemberId = insertMember(foreignOrgId,
                insertUser("case-foreign-" + suffix + "@example.com"));
        claimId = insertExpenseClaim(orgId, memberId);
        foreignClaimId = insertExpenseClaim(foreignOrgId, foreignMemberId);
        commitmentId = insertCommitment(orgId);
        foreignCommitmentId = insertCommitment(foreignOrgId);
    }

    @Test
    void v12MigrationRunsCleanlyAndEvolvesTheApprovalCaseShape() {
        // The Spring context has already migrated this MySQL container to the
        // latest Flyway head; assert the evolved shape is actually present.
        var subjectCheck = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND CONSTRAINT_NAME = 'chk_approval_case_subject'
                """, Integer.class);
        assertThat(subjectCheck).isEqualTo(1);

        var commitmentColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'approval_case'
                  AND COLUMN_NAME = 'budget_commitment_id'
                """, Integer.class);
        assertThat(commitmentColumns).isEqualTo(1);

        var expenseNullable = jdbc.queryForObject("""
                SELECT IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'approval_case'
                  AND COLUMN_NAME = 'expense_claim_id'
                """, String.class);
        assertThat(expenseNullable).isEqualTo("YES");
    }

    @Test
    void existingExpenseApprovalCaseStillCreates() {
        var caseId = insertCase(orgId, claimId, null);
        assertThat(caseId).isPositive();
        assertThat(approvalCaseSubject(caseId, "expense_claim_id")).isEqualTo(claimId);
        assertThat(approvalCaseSubject(caseId, "budget_commitment_id")).isNull();
    }

    @Test
    void budgetCommitmentApprovalCaseCreates() {
        var caseId = insertCase(orgId, null, commitmentId);
        assertThat(caseId).isPositive();
        assertThat(approvalCaseSubject(caseId, "expense_claim_id")).isNull();
        assertThat(approvalCaseSubject(caseId, "budget_commitment_id")).isEqualTo(commitmentId);
    }

    @Test
    void rejectsApprovalCaseWithNoSubject() {
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,NULL,NULL,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId));
    }

    @Test
    void rejectsApprovalCaseWithBothSubjects() {
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,?,?,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, claimId, commitmentId));
    }

    @Test
    void rejectsCommitmentOfAnotherOrganization() {
        // Same-org composite FK: a case of org A cannot reference a commitment
        // of org B, exactly like the expense side.
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,NULL,?,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, foreignCommitmentId));
    }

    @Test
    void rejectsSecondApprovalCaseForTheSameCommitment() {
        insertCase(orgId, null, commitmentId);
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,NULL,?,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, commitmentId));
    }

    @Test
    void expenseIntegrityIsFullyPreserved() {
        // The V10 expense side must keep working: same-org FK rejected for a
        // foreign expense, and the case-identity uniqueness intact.
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,?,NULL,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, foreignClaimId));

        var caseId = insertCase(orgId, claimId, null);
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,?,NULL,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, claimId));

        // The composite identity used by expense_claim.approval_case_id
        // (id, expense_claim_id, org_id) still exists for expense cases.
        var composite = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'approval_case'
                  AND INDEX_NAME = 'uq_approval_case_id_expense_org'
                """, Integer.class);
        assertThat(composite).isEqualTo(1);
    }

    @Test
    void approvalActionsStayAppendOnlyForCommitmentCases() {
        var caseId = insertCase(orgId, null, commitmentId);
        jdbc.update("""
                INSERT INTO approval_action(
                    org_id,approval_case_id,actor_member_id,action_type,
                    from_state,to_state,comment,created_at)
                VALUES (?,?,?,'SUBMIT','NONE','REQUESTED',NULL,UTC_TIMESTAMP(6))
                """, orgId, caseId, memberId);
        jdbc.update("""
                INSERT INTO approval_action(
                    org_id,approval_case_id,actor_member_id,action_type,
                    from_state,to_state,comment,created_at)
                VALUES (?,?,?,'APPROVE','REQUESTED','ACTIVE',NULL,UTC_TIMESTAMP(6))
                """, orgId, caseId, memberId);
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_action
                WHERE org_id=? AND approval_case_id=?
                """, Integer.class, orgId, caseId);
        assertThat(count).isEqualTo(2);

        // A commitment case cannot be hijacked by an expense subject pointer.
        assertMySqlRejects(() -> jdbc.update("""
                INSERT INTO approval_action(
                    org_id,approval_case_id,actor_member_id,action_type,
                    from_state,to_state,comment,created_at)
                VALUES (?,?,?,'APPROVE','REQUESTED','ACTIVE',NULL,UTC_TIMESTAMP(6))
                """, foreignOrgId, caseId, foreignMemberId));
    }

    // -- helpers --------------------------------------------------------------

    /**
     * A MySQL integrity rejection: the statement must fail with a real MySQL
     * constraint error (CHECK 3819, FK 1452, duplicate 1062). Spring maps the
     * CHECK error to UncategorizedSQLException, so the root SQL error code is
     * the stable contract instead of the Spring exception type.
     */
    private static void assertMySqlRejects(ThrowingRunnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(problem -> {
                    var cause = ((DataAccessException) problem).getRootCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    var code = ((SQLException) cause).getErrorCode();
                    assertThat(code).isIn(3819, 1452, 1062);
                });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private Long approvalCaseSubject(long caseId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM approval_case WHERE id=? AND org_id=?",
                Long.class, caseId, orgId);
    }

    private long insertCase(long org, Long expenseClaim, Long commitment) {
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,budget_commitment_id,
                    status,created_at,updated_at)
                VALUES (?,?,?,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, expenseClaim, commitment);
        return jdbc.queryForObject("""
                SELECT id FROM approval_case
                WHERE org_id=? AND expense_claim_id <=> ? AND budget_commitment_id <=> ?
                """, Long.class, org, expenseClaim, commitment);
    }

    private long insertCommitment(long org) {
        var periodId = insertPeriod(org);
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'ORG',?, 'CNY', 100.00000000, 0, 0, 'ACTIVE', 0,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, org, periodId, org);
        var budgetId = jdbc.queryForObject("""
                SELECT id FROM budget WHERE org_id=? AND billing_period_id=?
                """, Long.class, org, periodId);
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,
                    approved_amount,remaining_amount,version,created_at,updated_at)
                VALUES (?,?,'REQUESTED',1.00000000,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, budgetId);
        return jdbc.queryForObject("""
                SELECT id FROM budget_commitment WHERE org_id=? AND budget_id=?
                """, Long.class, org, budgetId);
    }

    private long insertExpenseClaim(long org, long member) {
        jdbc.update("""
                INSERT INTO expense_claim(
                    org_id,claimant_member_id,evidence_id,expense_date,amount,currency,status,
                    current_allocation_decision_id,approval_case_id,version,created_at,updated_at)
                VALUES (?,?,NULL,'2026-08-01',10.00000000,'CNY','DRAFT',NULL,NULL,0,
                        UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, member);
        return jdbc.queryForObject(
                "SELECT id FROM expense_claim WHERE org_id=? AND claimant_member_id=?",
                Long.class, org, member);
    }

    private long insertPeriod(long org) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,?,?,'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, AUG_1, SEP_1);
        return jdbc.queryForObject("""
                SELECT id FROM billing_period WHERE org_id=? AND period_start=? AND period_end=?
                """, Long.class, org, AUG_1, SEP_1);
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Case Worker");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    private long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }
}
