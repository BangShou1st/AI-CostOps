package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * AIC-044 atomic activation: one MySQL transaction locks the billing period
 * (require OPEN), performs the atomic conditional budget UPDATE
 * (committed += amount where status='ACTIVE' and available >= amount), moves
 * the commitment REQUESTED -> ACTIVE with approved = remaining = requested,
 * transitions the approval case PENDING -> APPROVED, appends exactly one
 * APPROVE action, audits, and finalizes idempotency. Any failure leaves no
 * half state and never exceeds the available capacity.
 */
@SpringBootTest
@Tag("integration")
class CommitmentActivationIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;

    @Test
    void approveActivatesCommitmentAndReservesBudgetAtomically() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "100.00000000");

        var detail = commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-ok-1");

        assertThat(detail.status().name()).isEqualTo("ACTIVE");
        assertThat(detail.requestedAmount()).isEqualByComparingTo("100.00000000");
        assertThat(detail.approvedAmount()).isEqualByComparingTo("100.00000000");
        assertThat(detail.remainingAmount()).isEqualByComparingTo("100.00000000");
        assertThat(detail.approvalStatus().name()).isEqualTo("APPROVED");
        assertThat(detail.version()).isEqualTo(1);
        assertThat(detail.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "APPROVE");
        // The atomic counter move is exact and the version bumped once.
        assertThat(budgetCommitted(budgetId)).isEqualTo("100.00000000");
        assertThat(budgetVersion(budgetId)).isEqualTo(1);
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("APPROVED");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
        assertThat(auditCount("COMMITMENT_ACTIVATED")).isEqualTo(1);
    }

    @Test
    void approveReplaysIdempotentlyWithoutDoubleCounting() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "50.00000000");

        var first = commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-replay-1");
        var replayed = commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-replay-1");

        assertThat(replayed.id()).isEqualTo(first.id());
        assertThat(replayed.status().name()).isEqualTo("ACTIVE");
        assertThat(budgetCommitted(budgetId)).isEqualTo("50.00000000");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
        assertThat(auditCount("COMMITMENT_ACTIVATED")).isEqualTo(1);
        assertThat(commitmentVersion(commitmentId)).isEqualTo(1);
    }

    @Test
    void approveWithSameKeyButDifferentBodyIs409() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "50.00000000");

        commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-conflict-1");

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(5), "appr-conflict-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });
    }

    @Test
    void approveRejectsWhenAvailableIsInsufficient() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "10.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000001");

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-insufficient-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.BUDGET_INSUFFICIENT);
                });

        // Nothing moved: no committed, no status change, no case transition.
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("REQUESTED");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("PENDING");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_ACTIVATED")).isZero();
    }

    @Test
    void approveRejectsWhenPeriodIsClosing() {
        var periodId = insertCurrentBillingPeriod(orgId, "CLOSING");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-closing-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.PERIOD_NOT_OPEN);
                });

        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("REQUESTED");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("PENDING");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_ACTIVATED")).isZero();
    }

    @Test
    void approveRejectsWhenPeriodIsClosed() {
        var periodId = insertCurrentBillingPeriod(orgId, "CLOSED");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-closed-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.PERIOD_NOT_OPEN);
                });

        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("REQUESTED");
    }

    @Test
    void approveRejectsStaleVersion() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(3), "appr-stale-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });
    }

    @Test
    void approveRejectsAnAlreadyActivatedCommitment() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-twice-1");

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(1), "appr-twice-2"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });

        // Still exactly one activation's worth of side effects.
        assertThat(budgetCommitted(budgetId)).isEqualTo("10.00000000");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
        assertThat(auditCount("COMMITMENT_ACTIVATED")).isEqualTo(1);
    }

    @Test
    void approveRejectsWrongOrgCommitmentWithPrivacy404() {
        var foreignPeriod = insertCurrentBillingPeriod(foreignOrgId, "OPEN");
        var foreignBudget = insertBudgetRow(foreignOrgId, foreignPeriod, "ORG", foreignOrgId,
                "CNY", "1000.00000000", "0.00000000", "0.00000000");
        var foreignCommitment = insertRequested(foreignOrgId, foreignBudget, "10.00000000",
                foreignRequesterMemberId);

        assertThatThrownBy(() -> commands.approve(reviewerUser(), foreignCommitment,
                new ApproveCommitmentCommand(0), "appr-foreign-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void approveRequiresPermissionBeforeAnyLookup() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.approve(noPermissionUser(), commitmentId,
                new ApproveCommitmentCommand(0), "appr-noperm-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo(ProblemCode.FORBIDDEN);
                });
    }

    @Test
    void approveRejectsBudgetOutsideTheGrantedScope() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        // ORG-scoped budget: the PROJECT-scoped reviewer must not see it.
        var orgBudget = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var orgCommitment = insertRequested(orgId, orgBudget, "10.00000000");

        assertThatThrownBy(() -> commands.approve(scopedUser(), orgCommitment,
                new ApproveCommitmentCommand(0), "appr-scope-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });

        // ... but the same scoped reviewer can activate a budget of their project.
        var projectBudget = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var projectCommitment = insertRequested(orgId, projectBudget, "10.00000000");
        var detail = commands.approve(scopedUser(), projectCommitment,
                new ApproveCommitmentCommand(0), "appr-scope-2");
        assertThat(detail.status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void approveIsExactAtTheCapacityBoundary() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "10.00000000", "0.00000000", "0.00000000");

        var exact = insertRequested(orgId, budgetId, "10.00000000");
        var detail = commands.approve(reviewerUser(), exact,
                new ApproveCommitmentCommand(0), "appr-boundary-1");
        assertThat(detail.status().name()).isEqualTo("ACTIVE");
        assertThat(budgetCommitted(budgetId)).isEqualTo("10.00000000");

        // A second commitment of any positive amount now has zero headroom.
        var over = insertRequested(orgId, budgetId, "0.00000001");
        assertThatThrownBy(() -> commands.approve(reviewerUser(), over,
                new ApproveCommitmentCommand(0), "appr-boundary-2"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.code()).isEqualTo(ProblemCode.BUDGET_INSUFFICIENT);
                });
        assertThat(budgetCommitted(budgetId)).isEqualTo("10.00000000");
    }

    private long insertRequested(long org, long budgetId, String requested) {
        return insertRequested(org, budgetId, requested, requesterMemberId);
    }

    private long insertRequested(long org, long budgetId, String requested,
            long actorMemberId) {
        var commitmentId = insertCommitmentRow(org, budgetId, "REQUESTED", requested,
                null, null, 0);
        var caseId = insertCommitmentCase(org, commitmentId, "PENDING");
        insertApprovalActionRow(org, caseId, actorMemberId, "SUBMIT", "NONE", "REQUESTED");
        return commitmentId;
    }
}
