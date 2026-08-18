package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.ReleaseCommitmentCommand;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * AIC-045 release: ACTIVE / PARTIALLY_CONSUMED → RELEASED frees the exact
 * outstanding remainder in one MySQL transaction — committed_amount is
 * decremented by R inside the locked period/budget/commitment chain, the
 * remainder is zeroed, versions bump, the audit fires, and the idempotency
 * row finalizes. Other states are rejected; committed and remaining can
 * never go negative; the commitment row is history, never deleted.
 */
@SpringBootTest
@Tag("integration")
class CommitmentReleaseIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;

    @Test
    void releaseActiveCommitmentFreesTheFullRemainder() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "50.00000000");

        var detail = commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(1), "rel-1");

        assertThat(detail.status().name()).isEqualTo("RELEASED");
        assertThat(detail.remainingAmount()).isEqualByComparingTo("0.00000000");
        assertThat(detail.version()).isEqualTo(2);
        assertThat(commitmentStatus(commitmentId)).isEqualTo("RELEASED");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("0.00000000");
        // The committed counter dropped by exactly R; the approval case stays
        // APPROVED (release is not an approval transition).
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(budgetVersion(budgetId)).isEqualTo(1);
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("APPROVED");
        assertThat(auditCount("COMMITMENT_RELEASED")).isEqualTo(1);
    }

    @Test
    void releasePartiallyConsumedCommitmentFreesTheRemainder() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "30.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "PARTIALLY_CONSUMED",
                "50.00000000", "50.00000000", "30.00000000", 2);
        insertCommitmentCase(orgId, commitmentId, "APPROVED");

        var detail = commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(2), "rel-partial-1");

        assertThat(detail.status().name()).isEqualTo("RELEASED");
        assertThat(detail.remainingAmount()).isEqualByComparingTo("0.00000000");
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("0.00000000");
        assertThat(auditCount("COMMITMENT_RELEASED")).isEqualTo(1);
    }

    @Test
    void releaseRejectsEveryInvalidState() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        var requested = insertCommitmentRow(orgId, budgetId, "REQUESTED",
                "10.00000000", null, null, 0);
        var consumed = insertCommitmentRow(orgId, budgetId, "CONSUMED",
                "10.00000000", "10.00000000", "0.00000000", 2);
        var released = insertCommitmentRow(orgId, budgetId, "RELEASED",
                "10.00000000", "10.00000000", "0.00000000", 2);
        var rejected = insertCommitmentRow(orgId, budgetId, "REJECTED",
                "10.00000000", null, null, 1);
        var canceled = insertCommitmentRow(orgId, budgetId, "CANCELED",
                "10.00000000", null, null, 1);

        for (var entry : new long[][] {
                { requested, 0 }, { consumed, 2 }, { released, 2 },
                { rejected, 1 }, { canceled, 1 } }) {
            var commitmentId = entry[0];
            var version = entry[1];
            assertThatThrownBy(() -> commands.release(reviewerUser(), commitmentId,
                    new ReleaseCommitmentCommand(version), "rel-invalid-" + commitmentId))
                    .isInstanceOf(DomainException.class)
                    .satisfies(problem -> {
                        var exception = (DomainException) problem;
                        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                    });
        }

        // The commitment row is history, never deleted.
        var remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment WHERE org_id=? AND budget_id=?",
                Integer.class, orgId, budgetId);
        assertThat(remaining).isEqualTo(5);
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
    }

    @Test
    void releaseRejectsWhenPeriodIsNotOpen() {
        var periodId = insertCurrentBillingPeriod(orgId, "CLOSED");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "50.00000000");

        assertThatThrownBy(() -> commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(1), "rel-closed-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.PERIOD_NOT_OPEN);
                });

        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(budgetCommitted(budgetId)).isEqualTo("50.00000000");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("50.00000000");
    }

    @Test
    void releaseRejectsWrongOrgAndMissingPermission() {
        var foreignPeriod = insertCurrentBillingPeriod(foreignOrgId, "OPEN");
        var foreignBudget = insertBudgetRow(foreignOrgId, foreignPeriod, "ORG", foreignOrgId,
                "CNY", "1000.00000000", "0.00000000", "0.00000000");
        var foreignCommitment = insertActive(foreignOrgId, foreignBudget, "10.00000000",
                foreignRequesterMemberId);

        assertThatThrownBy(() -> commands.release(reviewerUser(), foreignCommitment,
                new ReleaseCommitmentCommand(1), "rel-foreign-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });

        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "10.00000000");
        assertThatThrownBy(() -> commands.release(noPermissionUser(), commitmentId,
                new ReleaseCommitmentCommand(1), "rel-noperm-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo(ProblemCode.FORBIDDEN);
                });
    }

    @Test
    void releaseReplaysIdempotentlyWithoutDoubleDecrement() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "50.00000000");

        commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(1), "rel-replay-1");
        var replayed = commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(1), "rel-replay-1");

        assertThat(replayed.status().name()).isEqualTo("RELEASED");
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("0.00000000");
        assertThat(commitmentVersion(commitmentId)).isEqualTo(2);
        assertThat(auditCount("COMMITMENT_RELEASED")).isEqualTo(1);
    }

    @Test
    void releaseRejectsStaleVersion() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertActive(orgId, budgetId, "50.00000000");

        assertThatThrownBy(() -> commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(7), "rel-stale-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });
        assertThat(budgetCommitted(budgetId)).isEqualTo("50.00000000");
    }

    // -- fixtures --------------------------------------------------------------

    private long insertActive(long org, long budgetId, String amount) {
        return insertActive(org, budgetId, amount, reviewerMemberId);
    }

    private long insertActive(long org, long budgetId, String amount, long actorMemberId) {
        var commitmentId = insertCommitmentRow(org, budgetId, "ACTIVE", amount,
                amount, amount, 1);
        var caseId = insertCommitmentCase(org, commitmentId, "APPROVED");
        insertApprovalActionRow(org, caseId, actorMemberId, "SUBMIT", "NONE", "REQUESTED");
        insertApprovalActionRow(org, caseId, actorMemberId, "APPROVE", "REQUESTED", "ACTIVE");
        jdbc.update("UPDATE budget SET committed_amount=committed_amount+? WHERE id=?",
                new BigDecimal(amount), budgetId);
        return commitmentId;
    }
}
