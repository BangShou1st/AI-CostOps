package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V10 migration contract: clean V1->V10 apply, the three M4 tables and their
 * constraints, the staged pointer FKs (expense_claim can only point at its own
 * approval case / allocation decision), the DB-level "one CONFIRMED decision
 * per expense" backstop, and that the M3 charge path still works unchanged.
 */
@SpringBootTest
@Tag("integration")
class V10MigrationIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong fixture = new AtomicLong();

    private long orgId;
    private long memberId;
    private long evidenceId;

    @BeforeEach
    void setUp() {
        var suffix = fixture.incrementAndGet() + "-" + System.nanoTime();
        orgId = insertOrganization("V10 Org " + suffix, "v10-" + suffix);
        var userId = insertUser("v10-" + suffix + "@example.com");
        memberId = insertMember(orgId, userId);
        evidenceId = insertEvidence(orgId, memberId, "v10-sha-" + suffix);
    }

    @Test
    void migratesAllVersionsThroughV10() {
        var successfulVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class);
        assertThat(successfulVersions).contains("10");
        assertThat(successfulVersions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void createsExpenseTablesAndClosingConstraints() {
        var tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('expense_claim','approval_case','approval_action')
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "expense_claim", "approval_case", "approval_action");

        var constraints = jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name IN (
                    'uq_allocation_decision_confirmed_expense',
                    'uq_allocation_decision_id_expense_org',
                    'fk_allocation_decision_expense_org',
                    'fk_expense_claim_current_allocation_decision',
                    'fk_expense_claim_approval_case',
                    'uq_approval_case_org_expense',
                    'uq_evidence_id_org'
                  )
                """, String.class);
        assertThat(constraints).containsExactlyInAnyOrder(
                "uq_allocation_decision_confirmed_expense",
                "uq_allocation_decision_id_expense_org",
                "fk_allocation_decision_expense_org",
                "fk_expense_claim_current_allocation_decision",
                "fk_expense_claim_approval_case",
                "uq_approval_case_org_expense",
                "uq_evidence_id_org");

        var confirmedExpenseColumn = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'allocation_decision'
                  AND column_name = 'confirmed_expense_claim_id'
                """, Integer.class);
        assertThat(confirmedExpenseColumn).isEqualTo(1);

        // The V9 charge uniqueness column is untouched.
        var confirmedChargeColumn = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'allocation_decision'
                  AND column_name = 'confirmed_charge_fact_id'
                """, Integer.class);
        assertThat(confirmedChargeColumn).isEqualTo(1);
    }

    @Test
    void expenseClaimPointersCanOnlyReferenceOwnCaseAndDecision() {
        var expenseId = insertExpense("DRAFT", null);
        var caseId = insertApprovalCase(expenseId);
        var decisionId = insertConfirmedDecision(expenseId);

        // Own case and own decision are valid pointer targets.
        jdbc.update("""
                UPDATE expense_claim
                SET approval_case_id = ?, current_allocation_decision_id = ?
                WHERE id = ? AND org_id = ?
                """, caseId, decisionId, expenseId, orgId);
        assertThat(currentApprovalCaseId(expenseId)).isEqualTo(caseId);
        assertThat(currentAllocationDecisionId(expenseId)).isEqualTo(decisionId);

        // A case of another expense cannot be referenced.
        var otherExpenseId = insertExpense("DRAFT", null);
        var otherCaseId = insertApprovalCase(otherExpenseId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE expense_claim SET approval_case_id = ? WHERE id = ? AND org_id = ?
                """, otherCaseId, expenseId, orgId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A decision about another expense cannot be referenced.
        var otherDecisionId = insertConfirmedDecision(otherExpenseId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE expense_claim SET current_allocation_decision_id = ?
                WHERE id = ? AND org_id = ?
                """, otherDecisionId, expenseId, orgId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneConfirmedExpenseDecisionIsAllowed() {
        var expenseId = insertExpense("APPROVED", null);
        insertConfirmedDecision(expenseId);
        assertThatThrownBy(() -> insertConfirmedDecision(expenseId))
                .isInstanceOf(DataIntegrityViolationException.class);
        var confirmed = jdbc.queryForList("""
                SELECT id FROM allocation_decision
                WHERE org_id = ? AND expense_claim_id = ? AND status = 'CONFIRMED'
                """, orgId, expenseId);
        assertThat(confirmed).hasSize(1);
    }

    @Test
    void chargePathStillWorksAlongsideExpenseDecisions() {
        var suffix = fixture.incrementAndGet() + "-" + System.nanoTime();
        var accountId = insertProviderAccount(suffix);
        var rawRecordId = insertConfirmedRawRecord(suffix, accountId);
        var chargeId = insertCharge(rawRecordId);
        var decisionId = insertConfirmedChargeDecision(chargeId);

        assertThat(jdbc.queryForObject("""
                SELECT current_allocation_decision_id FROM charge_fact WHERE id = ? AND org_id = ?
                """, Long.class, chargeId, orgId)).isEqualTo(decisionId);

        // A charge decision and an expense decision coexist without tripping
        // either confirmed-* uniqueness constraint.
        var expenseId = insertExpense("APPROVED", null);
        insertConfirmedDecision(expenseId);
        assertThat(jdbc.queryForList("""
                SELECT id FROM allocation_decision
                WHERE org_id = ? AND status = 'CONFIRMED'
                """, orgId)).hasSize(2);
    }

    // -- fixtures -------------------------------------------------------------

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "V10 User");
        return jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized = ?", Long.class, email);
    }

    private long insertMember(long org, long user) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,employee_no,status,joined_at)
                VALUES (?,?,'V10-EMP','ACTIVE',UTC_TIMESTAMP(6))
                """, org, user);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id = ? AND user_id = ?",
                Long.class, org, user);
    }

    private long insertEvidence(long org, long member, String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,'receipt.pdf','application/pdf',10,?,'AVAILABLE',NULL,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, member);
        return jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id = ? AND sha256 = ?", Long.class, org, sha256);
    }

    private long insertExpense(String status, String amount) {
        jdbc.update("""
                INSERT INTO expense_claim(
                    org_id,claimant_member_id,evidence_id,expense_date,amount,currency,status,
                    current_allocation_decision_id,approval_case_id,version,created_at,updated_at)
                VALUES (?,?,?,'2026-08-01',?,'CNY',?,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, memberId, evidenceId,
                amount == null ? "100.00000000" : amount, status);
        return jdbc.queryForObject(
                "SELECT id FROM expense_claim WHERE org_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
    }

    private long insertApprovalCase(long expenseId) {
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,status,created_at,updated_at)
                VALUES (?,?,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, expenseId);
        return jdbc.queryForObject("""
                SELECT id FROM approval_case WHERE org_id = ? AND expense_claim_id = ?
                """, Long.class, orgId, expenseId);
    }

    private long insertConfirmedDecision(long expenseId) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?,'EXPENSE_CLAIM',NULL,?,'MANUAL',NULL,'CONFIRMED',?,UTC_TIMESTAMP(6))
                """, orgId, expenseId, memberId);
        return jdbc.queryForObject("""
                SELECT id FROM allocation_decision
                WHERE org_id = ? AND expense_claim_id = ? AND status = 'CONFIRMED'
                ORDER BY id DESC LIMIT 1
                """, Long.class, orgId, expenseId);
    }

    private long insertProviderAccount(String suffix) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'V10 Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GLM-" + suffix);
        return jdbc.queryForObject("""
                SELECT id FROM provider_account
                WHERE org_id = ? AND provider_code = ?
                """, Long.class, orgId, "GLM-" + suffix);
    }

    private long insertConfirmedRawRecord(String suffix, long accountId) {
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,'test-parser-v1','PENDING',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "GLM-" + suffix, "FILE_EXPORT", memberId);
        var batchId = jdbc.queryForObject(
                "SELECT id FROM import_batch WHERE org_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,available_at,lease_version,
                    parser_version,records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',UTC_TIMESTAMP(6),0,'test-parser-v1',0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id = ? AND attempt_no = 1
                """, Long.class, batchId);
        jdbc.update("UPDATE import_batch SET status='CONFIRMED', confirmed_attempt_id=? WHERE id=?",
                attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,raw_payload,normalize_status,created_at)
                VALUES (?,0,?,JSON_OBJECT(),'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "v10-record-" + suffix);
        return jdbc.queryForObject("""
                SELECT id FROM raw_provider_record
                WHERE import_attempt_id = ? AND record_index = 0
                """, Long.class, attemptId);
    }

    private long insertCharge(long rawRecordId) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    review_status,created_at)
                VALUES (?,?,0,?,?,'10.00000000','CNY','CLEAN',UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, "GLM-V10", "USAGE");
        return jdbc.queryForObject("""
                SELECT id FROM charge_fact WHERE org_id = ? AND raw_record_id = ?
                """, Long.class, orgId, rawRecordId);
    }

    private long insertConfirmedChargeDecision(long chargeId) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?,'CHARGE_FACT',?,NULL,'MANUAL',NULL,'CONFIRMED',?,UTC_TIMESTAMP(6))
                """, orgId, chargeId, memberId);
        var decisionId = jdbc.queryForObject("""
                SELECT id FROM allocation_decision
                WHERE org_id = ? AND charge_fact_id = ? AND status = 'CONFIRMED'
                """, Long.class, orgId, chargeId);
        jdbc.update("""
                UPDATE charge_fact SET current_allocation_decision_id = ?
                WHERE id = ? AND org_id = ?
                """, decisionId, chargeId, orgId);
        return decisionId;
    }

    private Long currentApprovalCaseId(long expenseId) {
        return jdbc.queryForObject(
                "SELECT approval_case_id FROM expense_claim WHERE id = ? AND org_id = ?",
                Long.class, expenseId, orgId);
    }

    private Long currentAllocationDecisionId(long expenseId) {
        return jdbc.queryForObject(
                "SELECT current_allocation_decision_id FROM expense_claim WHERE id = ? AND org_id = ?",
                Long.class, expenseId, orgId);
    }
}
