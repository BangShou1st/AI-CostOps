package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.application.AllocationDecisionCommandService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two DRAFT decisions of the same APPROVED expense confirmed concurrently:
 * exactly one reaches CONFIRMED (CAS + expense pointer + the DB
 * uq_allocation_decision_confirmed_expense backstop), the loser gets a 409,
 * and the expense ends with exactly one current decision pointer.
 */
@SpringBootTest
@Tag("integration")
class ExpenseAllocationUniqueConfirmIntegrationTest extends ExpenseTestSupport {

    @Autowired
    private AllocationDecisionCommandService commands;

    private long expenseId;
    private long costCenterId;

    @BeforeEach
    void setUpExpense() {
        costCenterId = insertTarget("cost_center", orgId, "exp-uc-c-" + System.nanoTime());
        expenseId = insertExpenseDraftFor(orgId, employeeMemberId, "10.00000000", "CNY", "APPROVED");
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,status,created_at,updated_at)
                VALUES (?,?,'APPROVED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, expenseId);
        jdbc.update("UPDATE expense_claim SET approval_case_id="
                + "(SELECT id FROM approval_case WHERE expense_claim_id=?) WHERE id=?",
                expenseId, expenseId);
    }

    @Test
    void twoConcurrentConfirmsExactlyOneWins() throws Exception {
        var decisionA = insertDirectDraft(expenseId);
        var decisionB = insertDirectDraft(expenseId);

        var outcomes = runConcurrently(
                () -> commands.confirm(financeUser(), decisionA, "unique-confirm-a"),
                () -> commands.confirm(financeUser(), decisionB, "unique-confirm-b"));

        assertThat(outcomes.successCount()).isEqualTo(1);
        assertThat(outcomes.conflictCount()).isEqualTo(1);

        var confirmed = jdbc.queryForList("""
                SELECT id FROM allocation_decision
                WHERE org_id=? AND expense_claim_id=? AND status='CONFIRMED'
                """, orgId, expenseId);
        assertThat(confirmed).hasSize(1);
        var winner = ((Number) confirmed.get(0).get("id")).longValue();
        assertThat(jdbc.queryForObject(
                "SELECT current_allocation_decision_id FROM expense_claim WHERE id=?",
                Long.class, expenseId)).isEqualTo(winner);
        // the loser stays DRAFT and never overwrites the pointer
        var loser = winner == decisionA ? decisionB : decisionA;
        assertThat(jdbc.queryForObject(
                "SELECT status FROM allocation_decision WHERE id=?",
                String.class, loser)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE event_type='ALLOCATION_DECISION_CONFIRMED' AND subject_id=?
                """, Integer.class, expenseId)).isEqualTo(1);
    }

    private long insertDirectDraft(long expenseId) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?,'EXPENSE_CLAIM',NULL,?,'MANUAL',NULL,'DRAFT',?,UTC_TIMESTAMP(6))
                """, orgId, expenseId, financeMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT id FROM allocation_decision WHERE org_id=? AND expense_claim_id=?"
                        + " AND status='DRAFT' ORDER BY id DESC LIMIT 1",
                Long.class, orgId, expenseId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?,?,0,'10.00000000','CNY',NULL,?,NULL,UTC_TIMESTAMP(6))
                """, orgId, decisionId, costCenterId);
        return decisionId;
    }

    private record RaceOutcomes(int successCount, int conflictCount) {
    }

    private RaceOutcomes runConcurrently(ThrowingRunnable first, ThrowingRunnable second)
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = List.of(pool.submit(task(first, start)), pool.submit(task(second, start)));
            start.countDown();
            int success = 0;
            int conflict = 0;
            for (Future<Void> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    success++;
                } catch (java.util.concurrent.ExecutionException execution) {
                    if (execution.getCause() instanceof DomainException domain
                            && domain.status().value() == 409) {
                        conflict++;
                    } else {
                        throw execution;
                    }
                }
            }
            return new RaceOutcomes(success, conflict);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static java.util.concurrent.Callable<Void> task(ThrowingRunnable runnable,
            CountDownLatch start) {
        return () -> {
            start.await();
            runnable.run();
            return null;
        };
    }

    private AuthenticatedUser financeUser() {
        return new AuthenticatedUser(financeUserId, 7);
    }
}