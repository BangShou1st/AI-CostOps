package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand;
import com.aicostops.allocation.application.AllocationDecisionCommandService;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.expense.application.ExpenseQueryService;
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
 * Expense allocation confirm: exact-sum validation, the confirmed decision
 * pointer on the claim, postingReady flipping to true, and the second-confirm
 * rejection. The DB uniqueness backstop is exercised by
 * {@link ExpenseAllocationUniqueConfirmIntegrationTest}.
 */
@SpringBootTest
@Tag("integration")
class ExpenseAllocationConfirmIntegrationTest extends ExpenseTestSupport {

    @Autowired
    private AllocationDecisionCommandService commands;
    @Autowired
    private ExpenseQueryService expenseQueries;

    private long projectId;
    private long costCenterId;

    @BeforeEach
    void setUpTargets() {
        projectId = insertTarget("project", orgId, "exp-conf-p-" + System.nanoTime());
        costCenterId = insertTarget("cost_center", orgId, "exp-conf-c-" + System.nanoTime());
    }

    @Test
    void confirmWritesPointerAndMakesPostingReady() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, new ManualDraftCommand(List.of(
                        new AllocationLineCommand(new BigDecimal("4.00000000"), "CNY",
                                projectId, null, null),
                        new AllocationLineCommand(new BigDecimal("6.00000000"), "CNY",
                                null, costCenterId, null))),
                "confirm-ok-draft");

        var confirmed = commands.confirm(financeUser(), draft.decision().id(), "confirm-ok");
        assertThat(confirmed.decision().status().name()).isEqualTo("CONFIRMED");
        assertThat(confirmed.decision().expenseClaimId()).isEqualTo(expenseId);

        var pointer = jdbc.queryForObject(
                "SELECT current_allocation_decision_id FROM expense_claim WHERE id=?",
                Long.class, expenseId);
        assertThat(pointer).isEqualTo(draft.decision().id());

        var detail = expenseQueries.getForReview(financeUser(), expenseId);
        assertThat(detail.postingReady()).isTrue();
        assertThat(auditCount("ALLOCATION_DECISION_CONFIRMED")).isEqualTo(1);
        // audit subject is the expense claim
        assertThat(jdbc.queryForObject("""
                SELECT subject_type FROM audit_event
                WHERE event_type='ALLOCATION_DECISION_CONFIRMED' ORDER BY id DESC LIMIT 1
                """, String.class)).isEqualTo("EXPENSE_CLAIM");
    }

    @Test
    void sumMismatchIsRejectedAtConfirm() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, new ManualDraftCommand(List.of(
                        new AllocationLineCommand(new BigDecimal("9.00000000"), "CNY",
                                projectId, null, null))),
                "confirm-sum-draft");

        assertThatThrownBy(() -> commands.confirm(financeUser(), draft.decision().id(),
                "confirm-sum"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("ALLOCATION_SUM_MISMATCH");
                });
        assertThat(jdbc.queryForObject(
                "SELECT current_allocation_decision_id FROM expense_claim WHERE id=?",
                Long.class, expenseId)).isNull();
    }

    @Test
    void secondConfirmAfterSuccessIsRejected() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "confirm-2nd-draft");
        commands.confirm(financeUser(), draft.decision().id(), "confirm-2nd-1");

        var secondDraft = insertDirectDraft(expenseId);
        assertThatThrownBy(() -> commands.confirm(financeUser(), secondDraft, "confirm-2nd-2"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("ALLOCATION_ALREADY_CONFIRMED");
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM allocation_decision
                WHERE org_id=? AND expense_claim_id=? AND status='CONFIRMED'
                """, Integer.class, orgId, expenseId)).isEqualTo(1);
    }

    @Test
    void currencyMismatchIsRejectedAtConfirm() {
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, new ManualDraftCommand(List.of(
                        new AllocationLineCommand(new BigDecimal("10.00000000"), "CNY",
                                projectId, null, null))),
                "confirm-cur-draft");
        jdbc.update("UPDATE allocation_line SET currency='USD' WHERE decision_id=?",
                draft.decision().id());

        assertThatThrownBy(() -> commands.confirm(financeUser(), draft.decision().id(),
                "confirm-cur"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
                });
    }

    @Test
    void nonApprovedAfterDraftIsRejectedAtConfirm() {
        // draft created while APPROVED, then the claim is demoted in DB
        var expenseId = insertApprovedExpense("10.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId, "10.00000000"), "confirm-status-draft");
        jdbc.update("UPDATE expense_claim SET status='SUBMITTED' WHERE id=?", expenseId);

        assertThatThrownBy(() -> commands.confirm(financeUser(), draft.decision().id(),
                "confirm-status"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("ALLOCATION_NOT_ELIGIBLE");
                });
    }

    private ManualDraftCommand manualDraft(long targetId, String amount) {
        return new ManualDraftCommand(List.of(new AllocationLineCommand(
                new BigDecimal(amount), "CNY", targetId, null, null)));
    }

    /** Inserts a second DRAFT decision directly (bypasses the one-draft rule). */
    private long insertDirectDraft(long expenseId) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?,'EXPENSE_CLAIM',NULL,?,'MANUAL',NULL,'DRAFT',?,UTC_TIMESTAMP(6))
                """, orgId, expenseId, financeMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM allocation_decision WHERE org_id=? AND expense_claim_id=?"
                        + " AND status='DRAFT' ORDER BY id DESC LIMIT 1",
                Long.class, orgId, expenseId);
    }

    private long insertApprovedExpense(String amount) {
        var expenseId = insertExpenseDraftFor(orgId, employeeMemberId, amount, "CNY", "APPROVED");
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,status,created_at,updated_at)
                VALUES (?,?,'APPROVED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, expenseId);
        jdbc.update("UPDATE expense_claim SET approval_case_id="
                + "(SELECT id FROM approval_case WHERE expense_claim_id=?) WHERE id=?",
                expenseId, expenseId);
        return expenseId;
    }

    private AuthenticatedUser financeUser() {
        return new AuthenticatedUser(financeUserId, 7);
    }
}