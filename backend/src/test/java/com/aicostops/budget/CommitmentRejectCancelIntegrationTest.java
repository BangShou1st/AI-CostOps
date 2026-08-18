package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.CancelCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RejectCommitmentCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * Reject / Cancel of a REQUESTED commitment: one MySQL transaction moves the
 * commitment and its approval case together, appends exactly one action,
 * audits, and finalizes idempotency. The budget counter is never touched.
 * Cancel ownership: the requester (SUBMIT actor) or any COMMITMENT_APPROVE
 * holder; ACTIVE commitments cannot be rejected or canceled.
 */
@SpringBootTest
@Tag("integration")
class CommitmentRejectCancelIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;

    @Test
    void rejectMovesRequestedCommitmentToRejected() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        var detail = commands.reject(reviewerUser(), commitmentId,
                new RejectCommitmentCommand(0, "Scope does not match"), "rej-1");

        assertThat(detail.status().name()).isEqualTo("REJECTED");
        assertThat(detail.approvalStatus().name()).isEqualTo("REJECTED");
        assertThat(detail.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "REJECT");
        assertThat(detail.history().get(1).comment()).isEqualTo("Scope does not match");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("REJECTED");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("REJECTED");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(auditCount("COMMITMENT_REJECTED")).isEqualTo(1);
    }

    @Test
    void rejectReplaysIdempotently() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        commands.reject(reviewerUser(), commitmentId,
                new RejectCommitmentCommand(0, "no"), "rej-replay-1");
        var replayed = commands.reject(reviewerUser(), commitmentId,
                new RejectCommitmentCommand(0, "no"), "rej-replay-1");

        assertThat(replayed.status().name()).isEqualTo("REJECTED");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
        assertThat(auditCount("COMMITMENT_REJECTED")).isEqualTo(1);
    }

    @Test
    void rejectFailsForAnActiveCommitment() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.reject(reviewerUser(), commitmentId,
                new RejectCommitmentCommand(1, "late"), "rej-active-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });

        // The ACTIVE commitment is untouched (release/consume are its exits).
        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(budgetCommitted(budgetId)).isEqualTo("10.00000000");
    }

    @Test
    void rejectRequiresPermissionAndPrivacy404() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.reject(noPermissionUser(), commitmentId,
                new RejectCommitmentCommand(0, "no"), "rej-noperm-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo(ProblemCode.FORBIDDEN);
                });

        var foreignPeriod = insertCurrentBillingPeriod(foreignOrgId, "OPEN");
        var foreignBudget = insertBudgetRow(foreignOrgId, foreignPeriod, "ORG", foreignOrgId,
                "CNY", "1000.00000000", "0.00000000", "0.00000000");
        var foreignCommitment = insertRequested(foreignOrgId, foreignBudget, "10.00000000",
                foreignRequesterMemberId);
        assertThatThrownBy(() -> commands.reject(reviewerUser(), foreignCommitment,
                new RejectCommitmentCommand(0, "no"), "rej-foreign-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void requesterCanCancelOwnCommitment() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        var detail = commands.cancel(requesterUser(), commitmentId,
                new CancelCommitmentCommand(0), "can-own-1");

        assertThat(detail.status().name()).isEqualTo("CANCELED");
        assertThat(detail.approvalStatus().name()).isEqualTo("CANCELED");
        assertThat(detail.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "CANCEL");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("CANCELED");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("CANCELED");
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(auditCount("COMMITMENT_CANCELED")).isEqualTo(1);
    }

    @Test
    void anotherRequesterCannotCancelSomeoneElsesCommitment() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        var otherUserId = insertUser("other-requester-" + fixtureCounter + "-"
                + System.nanoTime() + "@example.com");
        var otherMemberId = insertMember(orgId, otherUserId);
        assign("COMMIT_REQUESTER", "ORG", orgId, otherMemberId);

        assertThatThrownBy(() -> commands.cancel(new AuthenticatedUser(otherUserId, 7),
                commitmentId, new CancelCommitmentCommand(0), "can-other-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });

        // Still REQUESTED, untouched.
        assertThat(commitmentStatus(commitmentId)).isEqualTo("REQUESTED");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("PENDING");
    }

    @Test
    void reviewerCanCancelAnyRequestedCommitment() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        var detail = commands.cancel(reviewerUser(), commitmentId,
                new CancelCommitmentCommand(0), "can-reviewer-1");
        assertThat(detail.status().name()).isEqualTo("CANCELED");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("CANCELED");
        assertThat(auditCount("COMMITMENT_CANCELED")).isEqualTo(1);
    }

    @Test
    void cancelFailsForAnActiveCommitment() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "10.00000000");

        assertThatThrownBy(() -> commands.cancel(reviewerUser(), commitmentId,
                new CancelCommitmentCommand(1), "can-active-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });
        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(budgetCommitted(budgetId)).isEqualTo("10.00000000");
    }

    @Test
    void cancelReplaysIdempotently() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "10.00000000");

        commands.cancel(requesterUser(), commitmentId,
                new CancelCommitmentCommand(0), "can-replay-1");
        var replayed = commands.cancel(requesterUser(), commitmentId,
                new CancelCommitmentCommand(0), "can-replay-1");

        assertThat(replayed.status().name()).isEqualTo("CANCELED");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
        assertThat(auditCount("COMMITMENT_CANCELED")).isEqualTo(1);
    }

    // -- fixtures --------------------------------------------------------------

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

    private long insertActive(long org, long budgetId, String amount) {
        var commitmentId = insertCommitmentRow(org, budgetId, "ACTIVE", amount,
                amount, amount, 1);
        var caseId = insertCommitmentCase(org, commitmentId, "APPROVED");
        insertApprovalActionRow(org, caseId, requesterMemberId, "SUBMIT", "NONE", "REQUESTED");
        insertApprovalActionRow(org, caseId, reviewerMemberId, "APPROVE", "REQUESTED", "ACTIVE");
        jdbc.update("UPDATE budget SET committed_amount=committed_amount+? WHERE id=?",
                new BigDecimal(amount), budgetId);
        return commitmentId;
    }
}
