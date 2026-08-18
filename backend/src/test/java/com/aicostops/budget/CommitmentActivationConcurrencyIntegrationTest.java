package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AIC-044 mandatory concurrency evidence on the real MySQL Testcontainer:
 * 100 concurrent activations against one budget with capacity for 10 must
 * converge to exactly 10 ACTIVE commitments and committed_amount = 10 —
 * never more, never half state. Plus the same-commitment races: same key
 * yields one activation, different keys yield one winner.
 */
@SpringBootTest
@Tag("integration")
class CommitmentActivationConcurrencyIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;

    @Test
    void hundredConcurrentActivationsConvergeToExactCapacity() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "10.00000000", "0.00000000", "0.00000000");

        var commitmentIds = new ArrayList<Long>();
        for (var i = 0; i < 100; i++) {
            commitmentIds.add(insertRequested(orgId, budgetId, "1.00000000"));
        }

        var executor = Executors.newFixedThreadPool(16);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Outcome>>();
            for (var commitmentId : commitmentIds) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        var detail = commands.approve(reviewerUser(), commitmentId,
                                new ApproveCommitmentCommand(0),
                                "conc-100-" + commitmentId);
                        return Outcome.success(detail.id(), detail.status().name());
                    } catch (DomainException problem) {
                        return Outcome.failure(commitmentId, problem.code(),
                                problem.getMessage() + " :: " + problem.title());
                    }
                }));
            }
            start.countDown();

            var activated = 0;
            var insufficient = 0;
            var otherFailures = new ArrayList<String>();
            for (var future : futures) {
                var outcome = future.get(2, TimeUnit.MINUTES);
                if (outcome.activated) {
                    activated++;
                } else if (outcome.code == ProblemCode.BUDGET_INSUFFICIENT) {
                    insufficient++;
                } else {
                    otherFailures.add(outcome.commitmentId + ":" + outcome.code
                            + " :: " + outcome.message);
                }
            }
            assertThat(otherFailures).as("unexpected non-insufficient failures").isEmpty();
            assertThat(activated).isEqualTo(10);
            assertThat(insufficient).isEqualTo(90);

            // The counter converged exactly to capacity, never beyond.
            assertThat(budgetCommitted(budgetId)).isEqualTo("10.00000000");
            assertThat(budgetVersion(budgetId)).isEqualTo(10);

            // Every winner is fully consistent; every loser is untouched.
            for (var commitmentId : commitmentIds) {
                var status = commitmentStatus(commitmentId);
                if ("ACTIVE".equals(status)) {
                    assertThat(commitmentApproved(commitmentId)).isEqualTo("1.00000000");
                    assertThat(commitmentRemaining(commitmentId)).isEqualTo("1.00000000");
                    assertThat(approvalCaseStatus(commitmentId)).isEqualTo("APPROVED");
                    assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
                    assertThat(approvalActionTypes(commitmentId))
                            .containsExactly("SUBMIT", "APPROVE");
                } else {
                    assertThat(status).isEqualTo("REQUESTED");
                    assertThat(approvalCaseStatus(commitmentId)).isEqualTo("PENDING");
                    assertThat(approvalActionCount(commitmentId)).isEqualTo(1);
                    assertThat(approvalActionTypes(commitmentId)).containsExactly("SUBMIT");
                }
            }
            assertThat(auditCount("COMMITMENT_ACTIVATED")).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameCommitmentSameKeyProducesExactlyOneActivation() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "10.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "1.00000000");

        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Outcome>>();
            for (var i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        var detail = commands.approve(reviewerUser(), commitmentId,
                                new ApproveCommitmentCommand(0), "conc-same-key");
                        return Outcome.success(detail.id(), detail.status().name());
                    } catch (DomainException problem) {
                        return Outcome.failure(commitmentId, problem.code(),
                                problem.getMessage());
                    }
                }));
            }
            start.countDown();

            var statuses = new ArrayList<String>();
            for (var future : futures) {
                statuses.add(future.get(1, TimeUnit.MINUTES).status);
            }
            assertThat(statuses).containsExactly("ACTIVE", "ACTIVE");
            // Exactly one activation's worth of side effects.
            assertThat(budgetCommitted(budgetId)).isEqualTo("1.00000000");
            assertThat(commitmentVersion(commitmentId)).isEqualTo(1);
            assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
            assertThat(auditCount("COMMITMENT_ACTIVATED")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameCommitmentDifferentKeysHaveOneWinner() throws Exception {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "10.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "1.00000000");

        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Outcome>>();
            for (var i = 0; i < 2; i++) {
                final var key = "conc-diff-key-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        var detail = commands.approve(reviewerUser(), commitmentId,
                                new ApproveCommitmentCommand(0), key);
                        return Outcome.success(detail.id(), detail.status().name());
                    } catch (DomainException problem) {
                        return Outcome.failure(commitmentId, problem.code(),
                                problem.getMessage());
                    }
                }));
            }
            start.countDown();

            var activated = 0;
            var conflicted = 0;
            for (var future : futures) {
                var outcome = future.get(1, TimeUnit.MINUTES);
                if (outcome.activated) {
                    activated++;
                } else if (outcome.code == ProblemCode.STATE_CONFLICT) {
                    conflicted++;
                }
            }
            assertThat(activated).isEqualTo(1);
            assertThat(conflicted).isEqualTo(1);

            // One winner, no double counting, exactly one APPROVE action.
            assertThat(budgetCommitted(budgetId)).isEqualTo("1.00000000");
            assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
            assertThat(commitmentVersion(commitmentId)).isEqualTo(1);
            assertThat(approvalActionCount(commitmentId)).isEqualTo(2);
            assertThat(auditCount("COMMITMENT_ACTIVATED")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<String> approvalActionTypes(long commitmentId) {
        return jdbc.queryForList("""
                SELECT aa.action_type FROM approval_action aa
                JOIN approval_case ac ON ac.id=aa.approval_case_id
                WHERE ac.org_id=? AND ac.budget_commitment_id=?
                ORDER BY aa.created_at ASC, aa.id ASC
                """, String.class, orgId, commitmentId);
    }

    private long insertRequested(long org, long budgetId, String requested) {
        var commitmentId = insertCommitmentRow(org, budgetId, "REQUESTED", requested,
                null, null, 0);
        var caseId = insertCommitmentCase(org, commitmentId, "PENDING");
        insertApprovalActionRow(org, caseId, requesterMemberId, "SUBMIT", "NONE", "REQUESTED");
        return commitmentId;
    }

    private record Outcome(long commitmentId, boolean activated, String status,
            ProblemCode code, String message) {
        static Outcome success(long commitmentId, String status) {
            return new Outcome(commitmentId, true, status, null, null);
        }

        static Outcome failure(long commitmentId, ProblemCode code, String message) {
            return new Outcome(commitmentId, false, null, code, message);
        }
    }
}
