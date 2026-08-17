package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand;
import com.aicostops.allocation.application.AllocationDecisionCommandService;
import com.aicostops.allocation.application.AllocationDecisionQueryService;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Expense allocation is Finance-only. An actor who holds the allocation
 * permissions but NOT EXPENSE_REVIEW can still allocate CHARGE_FACT subjects
 * (M3 unchanged) but is refused every EXPENSE_CLAIM allocation operation with
 * 403. A finance actor who additionally holds EXPENSE_REVIEW is allowed.
 */
 @SpringBootTest
@Tag("integration")
class ExpenseAllocationFinanceOnlyIntegrationTest extends ExpenseTestSupport {

    private static final String JAN_1 = "2026-01-01 00:00:00.000000";
    private static final String FEB_1 = "2026-02-01 00:00:00.000000";

    @Autowired
    private AllocationDecisionCommandService commands;
    @Autowired
    private AllocationDecisionQueryService queries;

    private long projectId;
    private long costCenterId;
    private long projectOwnerUserId;
    private long projectOwnerMemberId;
    private long accountId;
    private long rawRecordId;
    private long chargeId;

    @BeforeEach
    void setUpTargetsAndCharges() {
        projectId = insertTarget("project", orgId, "fin-p-" + System.nanoTime());
        costCenterId = insertTarget("cost_center", orgId, "fin-c-" + System.nanoTime());
        projectOwnerUserId = insertUser("fin-owner-" + System.nanoTime() + "@example.com");
        projectOwnerMemberId = insertMember(orgId, projectOwnerUserId);
        createPermissionRole("EXPENSE_PROJECT_OWNER", List.of(
                "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM"));
        assign("EXPENSE_PROJECT_OWNER", orgId, projectOwnerMemberId);

        accountId = insertProviderAccount(orgId, "GLM-" + System.nanoTime());
        rawRecordId = insertConfirmedRawRecord(orgId, projectOwnerMemberId, accountId,
                "fin-owner");
        chargeId = insertCharge(rawRecordId);
    }

    // -- M3 regression: the project owner can still allocate a charge ---------

    @Test
    void projectOwnerCanStillAllocateCharge() {
        var draft = commands.createManualDraft(projectOwnerUser(), AllocationSubjectType.CHARGE_FACT,
                chargeId, new ManualDraftCommand(List.of(
                        new AllocationLineCommand(new BigDecimal("4.00000000"), "CNY",
                                projectId, null, null),
                        new AllocationLineCommand(new BigDecimal("6.00000000"), "CNY",
                                null, costCenterId, null))),
                "po-charge-draft");
        assertThat(draft.decision().status().name()).isEqualTo("DRAFT");

        var confirmed = commands.confirm(projectOwnerUser(), draft.decision().id(), "po-charge-confirm");
        assertThat(confirmed.decision().status().name()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT current_allocation_decision_id FROM charge_fact WHERE id=?",
                Long.class, chargeId)).isEqualTo(draft.decision().id());
    }

    // -- Expense allocation is refused for the project owner -------------------

    @Test
    void projectOwnerCannotListExpenseDecisions() {
        var expenseId = insertApprovedExpense("10.00000000");
        assertThatThrownBy(() -> queries.listByExpense(projectOwnerUser(), expenseId))
                .satisfies(this::assertForbidden);
    }

    @Test
    void projectOwnerCannotGetExpenseDecision() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "fin-draft-for-po");
        assertThatThrownBy(() -> queries.get(projectOwnerUser(), draft.decision().id()))
                .satisfies(this::assertForbidden);
    }

    @Test
    void projectOwnerCannotCreateExpenseManualDraft() {
        var expenseId = insertApprovedExpense("10.00000000");
        assertThatThrownBy(() -> commands.createManualDraft(projectOwnerUser(),
                AllocationSubjectType.EXPENSE_CLAIM, expenseId,
                manualDraft(projectId, "10.00000000"), "po-draft"))
                .satisfies(this::assertForbidden);
    }

    @Test
    void projectOwnerCannotReplaceExpenseLines() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "fin-draft-for-replace");
        assertThatThrownBy(() -> commands.replaceLines(projectOwnerUser(), draft.decision().id(),
                List.of(new AllocationLineCommand(
                        new BigDecimal("10.00000000"), "CNY", projectId, null, null))))
                .satisfies(this::assertForbidden);
    }

    @Test
    void projectOwnerCannotConfirmExpenseAllocation() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "fin-draft-for-confirm");
        assertThatThrownBy(() -> commands.confirm(projectOwnerUser(), draft.decision().id(), "po-confirm"))
                .satisfies(this::assertForbidden);
    }

    // -- Finance actor (EXPENSE_REVIEW) is still allowed -----------------------

    @Test
    void financeActorCanListGetCreateReplaceConfirmExpenseAllocation() {
        var expenseId = insertApprovedExpense("10.00000000");

        assertThat(queries.listByExpense(financeUser(), expenseId)).isEmpty();

        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "fin-draft");
        assertThat(queries.get(financeUser(), draft.decision().id()).decision().id())
                .isEqualTo(draft.decision().id());

        var replaced = commands.replaceLines(financeUser(), draft.decision().id(),
                List.of(new AllocationLineCommand(
                        new BigDecimal("10.00000000"), "CNY", projectId, null, null)));
        assertThat(replaced.decision().status().name()).isEqualTo("DRAFT");

        var confirmed = commands.confirm(financeUser(), draft.decision().id(), "fin-confirm");
        assertThat(confirmed.decision().status().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void replayedExpenseConfirmStillChecksCurrentExpenseReview() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "replay-draft");

        // First confirm succeeds: ALLOCATION_CONFIRM + EXPENSE_REVIEW present.
        var confirmed = commands.confirm(financeUser(), draft.decision().id(), "replay-key");
        assertThat(confirmed.decision().status().name()).isEqualTo("CONFIRMED");

        // Revoke EXPENSE_REVIEW while keeping ALLOCATION_CONFIRM, then flush the
        // IAM/permission cache so the next context load reflects the change.
        jdbc.update("""
                DELETE FROM role_permission
                WHERE role_id = (SELECT id FROM `role` WHERE code = 'EXPENSE_FINANCE')
                  AND permission_id = (SELECT id FROM permission WHERE code = 'EXPENSE_REVIEW')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Same actor, same decision, same idempotency key: the replay must NOT
        // return the cached success; the current EXPENSE_REVIEW gate must refuse.
        assertThatThrownBy(() -> commands.confirm(financeUser(), draft.decision().id(), "replay-key"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(403);
                    assertThat(domain.code().name()).isEqualTo("FORBIDDEN");
                });
    }

    // -- helpers ---------------------------------------------------------------

    private void assertForbidden(Throwable thrown) {
        var domain = (DomainException) thrown;
        assertThat(domain.status().value()).isEqualTo(403);
        assertThat(domain.code().name()).isEqualTo("FORBIDDEN");
    }

    private ManualDraftCommand manualDraft(long targetId, String amount) {
        return new ManualDraftCommand(List.of(new AllocationLineCommand(
                new BigDecimal(amount), "CNY", targetId, null, null)));
    }

    private AuthenticatedUser projectOwnerUser() {
        return new AuthenticatedUser(projectOwnerUserId, 7);
    }

    private AuthenticatedUser financeUser() {
        return new AuthenticatedUser(financeUserId, 7);
    }

    private long insertApprovedExpense(String amount) {
        var expenseId = insertExpenseDraftFor(orgId, projectOwnerMemberId, amount, "CNY", "APPROVED");
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,status,created_at,updated_at)
                VALUES (?,?,'APPROVED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, expenseId);
        jdbc.update("UPDATE expense_claim SET approval_case_id="
                + "(SELECT id FROM approval_case WHERE expense_claim_id=?) WHERE id=?",
                expenseId, expenseId);
        return expenseId;
    }

    private long insertProviderAccount(long org, String providerCode) {
        jdbc.update("""
                INSERT IGNORE INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, providerCode, "Fin Account");
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, org, providerCode);
    }

    private long insertConfirmedRawRecord(long org, long memberId, long account, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, account, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=1",
                Long.class, batchId);
        jdbc.update("UPDATE import_batch SET status='CONFIRMED', confirmed_attempt_id=? WHERE id=?",
                attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "fin-owner:" + suffix, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    private long insertCharge(long rawRecordId) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, rawRecordId);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,'CNY',?,?,?,UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, nextIndex, "10.00000000", JAN_1, FEB_1, "CLEAN");
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgId, rawRecordId);
    }

    @Override
    protected void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('EXPENSE_EMPLOYEE','EXPENSE_FINANCE','EXPENSE_PROJECT_OWNER')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('EXPENSE_EMPLOYEE','EXPENSE_FINANCE','EXPENSE_PROJECT_OWNER')");
    }
}