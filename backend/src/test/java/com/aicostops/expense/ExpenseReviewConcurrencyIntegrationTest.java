package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands.ApproveExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RejectExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.SubmitExpenseCommand;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.expense.application.ExpenseReviewCommandService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two reviewers racing approve vs. reject on the same SUBMITTED expense:
 * exactly one terminal result, the loser gets a 409, and the database holds a
 * single terminal expense state plus a single terminal approval case state.
 */
@SpringBootTest
@Tag("integration")
class ExpenseReviewConcurrencyIntegrationTest extends ExpenseTestSupport {

    private static final byte[] RECEIPT = "race-receipt".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ExpenseClaimCommandService commands;
    @Autowired
    private ExpenseReviewCommandService reviews;
    @Autowired
    private ExpenseEvidenceUploadService evidenceUploads;

    @Test
    void twoReviewersApproveRejectExactlyOneSucceeds() throws Exception {
        var expenseId = commands.create(employeeUser(), createCommand(), "race-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), expenseId, new SubmitExpenseCommand(1), "race-submit");

        var reviewerB = secondFinanceReviewer();

        var outcomes = runConcurrently(
                () -> reviews.approve(financeUser(), expenseId,
                        new ApproveExpenseCommand(2), "race-approve"),
                () -> reviews.reject(reviewerB, expenseId,
                        new RejectExpenseCommand(2, null), "race-reject"));

        assertThat(outcomes.successCount()).isEqualTo(1);
        assertThat(outcomes.conflictCount()).isEqualTo(1);

        var expenseState = jdbc.queryForObject(
                "SELECT status FROM expense_claim WHERE id=?", String.class, expenseId);
        var caseState = jdbc.queryForObject(
                "SELECT ac.status FROM approval_case ac JOIN expense_claim ec"
                        + " ON ec.approval_case_id=ac.id WHERE ec.id=?",
                String.class, expenseId);
        assertThat(expenseState).isIn("APPROVED", "REJECTED");
        assertThat(caseState).isEqualTo(expenseState);
        // exactly one terminal review action appended
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_action aa
                JOIN approval_case ac ON aa.approval_case_id=ac.id
                JOIN expense_claim ec ON ec.approval_case_id=ac.id
                WHERE ec.id=? AND aa.action_type IN ('APPROVE','REJECT')
                """, Integer.class, expenseId)).isEqualTo(1);
    }

    private AuthenticatedUser secondFinanceReviewer() {
        var userId = insertUser("finance-b-" + System.nanoTime() + "@example.com");
        var memberId = insertMember(orgId, userId);
        assign("EXPENSE_FINANCE", orgId, memberId);
        return new AuthenticatedUser(userId, 7);
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

    private AuthenticatedUser employeeUser() {
        return new AuthenticatedUser(employeeUserId, 7);
    }

    private AuthenticatedUser financeUser() {
        return new AuthenticatedUser(financeUserId, 7);
    }

    private CreateExpenseCommand createCommand() {
        return new CreateExpenseCommand(LocalDate.parse("2026-08-01"),
                new BigDecimal("100.00000000"), "CNY");
    }
}