package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand;
import com.aicostops.allocation.application.AllocationDecisionCommandService;
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
 * Expense allocation eligibility: only an APPROVED claim can create a manual
 * draft; employees have no allocation permissions; cross-org expenses are
 * invisible; currency/sum/target violations and duplicate manual drafts are
 * rejected. Confirm specifics are covered by
 * {@link ExpenseAllocationConfirmIntegrationTest}.
 */
@SpringBootTest
@Tag("integration")
class ExpenseAllocationEligibilityIntegrationTest extends ExpenseTestSupport {

    @Autowired
    private AllocationDecisionCommandService commands;

    private long projectId;
    private long costCenterId;

    @BeforeEach
    void setUpTargets() {
        projectId = insertTarget("project", orgId, "exp-p-" + System.nanoTime());
        costCenterId = insertTarget("cost_center", orgId, "exp-c-" + System.nanoTime());
    }

    @Test
    void approvedExpenseCanCreateManualDraft() {
        var expenseId = insertApprovedExpense("100.00000000");
        var draft = commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId), "elig-draft-ok");
        assertThat(draft.decision().subjectType()).isEqualTo(AllocationSubjectType.EXPENSE_CLAIM);
        assertThat(draft.decision().expenseClaimId()).isEqualTo(expenseId);
        assertThat(draft.decision().status().name()).isEqualTo("DRAFT");
    }

    @Test
    void nonApprovedExpenseCannotCreateManualDraft() {
        for (var status : List.of("DRAFT", "SUBMITTED", "NEEDS_INFO", "REJECTED", "CANCELED")) {
            var expenseId = insertExpenseDraftFor(orgId, employeeMemberId, "100.00000000",
                    "CNY", status);
            assertThatThrownBy(() -> commands.createManualDraft(financeUser(),
                    AllocationSubjectType.EXPENSE_CLAIM, expenseId,
                    manualDraft(projectId), "elig-draft-" + status))
                    .satisfies(thrown -> {
                        var domain = (DomainException) thrown;
                        assertThat(domain.status().value()).isEqualTo(409);
                        assertThat(domain.code().name()).isEqualTo("ALLOCATION_NOT_ELIGIBLE");
                    });
        }
    }

    @Test
    void employeeCannotAllocate() {
        var expenseId = insertApprovedExpense("100.00000000");
        assertThatThrownBy(() -> commands.createManualDraft(employeeUser(),
                AllocationSubjectType.EXPENSE_CLAIM, expenseId,
                manualDraft(projectId), "elig-employee"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(403);
                    assertThat(domain.code().name()).isEqualTo("FORBIDDEN");
                });
    }

    @Test
    void crossOrgExpenseIsNotFound() {
        var foreignExpenseId = insertExpenseDraftFor(foreignOrgId, employeeMemberId,
                "100.00000000", "CNY", "APPROVED");
        assertThatThrownBy(() -> commands.createManualDraft(financeUser(),
                AllocationSubjectType.EXPENSE_CLAIM, foreignExpenseId,
                manualDraft(projectId), "elig-cross-org"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(404);
                    assertThat(domain.code().name()).isEqualTo("RESOURCE_NOT_FOUND");
                });
    }

    @Test
    void currencyMismatchIsRejectedAtDraft() {
        var expenseId = insertApprovedExpense("100.00000000");
        assertThatThrownBy(() -> commands.createManualDraft(financeUser(),
                AllocationSubjectType.EXPENSE_CLAIM, expenseId,
                new ManualDraftCommand(List.of(new AllocationLineCommand(
                        new BigDecimal("100.00000000"), "USD", projectId, null, null))),
                "elig-currency"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(400);
                    assertThat(domain.code().name()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void invalidTargetIsRejectedAtDraft() {
        var expenseId = insertApprovedExpense("100.00000000");
        var archivedProject = insertTarget("project", orgId, "exp-arch-" + System.nanoTime());
        deactivateTarget("project", orgId, archivedProject);
        assertThatThrownBy(() -> commands.createManualDraft(financeUser(),
                AllocationSubjectType.EXPENSE_CLAIM, expenseId,
                new ManualDraftCommand(List.of(new AllocationLineCommand(
                        new BigDecimal("100.00000000"), "CNY", archivedProject, null, null))),
                "elig-target"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(400);
                    assertThat(domain.code().name()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void secondManualDraftConflicts() {
        var expenseId = insertApprovedExpense("100.00000000");
        commands.createManualDraft(financeUser(), AllocationSubjectType.EXPENSE_CLAIM,
                expenseId, manualDraft(projectId), "elig-draft-1");
        assertThatThrownBy(() -> commands.createManualDraft(financeUser(),
                AllocationSubjectType.EXPENSE_CLAIM, expenseId,
                manualDraft(costCenterId), "elig-draft-2"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("MANUAL_ALLOCATION_DRAFT_EXISTS");
                });
    }

    private ManualDraftCommand manualDraft(long targetId) {
        return new ManualDraftCommand(List.of(new AllocationLineCommand(
                new BigDecimal("100.00000000"), "CNY", targetId, null, null)));
    }

    private long insertApprovedExpense(String amount) {
        var expenseId = insertExpenseDraftFor(orgId, employeeMemberId, amount, "CNY", "APPROVED");
        // a submitted-then-approved claim carries an approval case
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

    private AuthenticatedUser employeeUser() {
        return new AuthenticatedUser(employeeUserId, 7);
    }
}