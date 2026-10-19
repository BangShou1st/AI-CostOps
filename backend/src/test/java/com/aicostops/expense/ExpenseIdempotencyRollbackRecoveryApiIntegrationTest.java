package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aicostops.expense.application.ExpenseAuditPort;
import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.expense.application.ExpenseIdempotencyStore;
import com.aicostops.shared.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real HTTP entry for the same-key rollback/recovery convergence the component
 * probe verified directly against MySQL (see the 2026-10-14 follow-up record):
 * the FIRST request reserves the idempotency key, writes the business rows and
 * then fails, rolling the whole transaction back; the SECOND request with the
 * same key waits on the unique key, resumes after the rollback, re-reserves
 * successfully and completes.
 *
 * <p>Synchronization evidence: the idempotency mapper is spied so the test can
 * schedule the two requests precisely, and the blocking itself is proven at the
 * DATABASE level, not by call timing. The FIRST request is parked inside its
 * transaction (an audit-stub latch) with the {@code api_idempotency} unique-key
 * insert already made but uncommitted; the test then polls the server's
 * {@code information_schema.innodb_trx} (through the container's root grant —
 * the application user has no PROCESS privilege) until the SECOND request's
 * transaction is in {@code trx_state='LOCK WAIT'} with its current statement
 * being an {@code INSERT INTO api_idempotency}, which proves the second request
 * has executed the reserve INSERT and is waiting on the first transaction's
 * unique-key lock. Only then is the first transaction released, and the second
 * reserve must complete as a consequence of the rollback. Every wait is bounded
 * and the latch and pool are always released.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class ExpenseIdempotencyRollbackRecoveryApiIntegrationTest extends ExpenseTestSupport {

    private static final byte[] RECEIPT = "rollback-recovery-receipt".getBytes(StandardCharsets.UTF_8);
    private static final String SUBMIT_KEY = "rb-submit-key";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseClaimCommandService commands;
    @Autowired ExpenseEvidenceUploadService evidenceUploads;
    @MockitoSpyBean ExpenseAuditPort audit;
    @MockitoSpyBean ExpenseIdempotencyStore idempotencyStore;

    @AfterEach
    void resetSpies() {
        org.mockito.Mockito.reset(audit, idempotencyStore);
    }

    /**
     * First request: HTTP submit with key K fails (audit injection) after the
     * status/action writes, rolling the transaction back. The second request with
     * the same key K is provably blocked on the first transaction's idempotency
     * unique key; once the first rolls back it re-reserves successfully and
     * completes with 200. Exactly one durable SUBMITTED result must remain.
     */
    @Test
    void sameKeySecondRequestCompletesAfterFirstRequestRollsBackInTransaction()
            throws Exception {
        var expenseId = commands.create(employeeUser(), createCommand(), "rb-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));

        var auditEntered = new CountDownLatch(1);
        var releaseAudit = new CountDownLatch(1);
        var injectOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            // Only the FIRST request is sabotaged; the second request must be
            // able to complete its own business writes after re-reserving.
            if (injectOnce.compareAndSet(true, false)) {
                auditEntered.countDown();
                if (!releaseAudit.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the first request");
                }
                throw new IllegalStateException("audit unavailable");
            }
            return invocation.callRealMethod();
        }).when(audit).submitted(anyLong(), anyLong(), anyLong(), anyString(), anyLong());

        var reserveAttempts = new AtomicInteger();
        var secondReserveEntered = new CountDownLatch(1);
        var secondReserveCompleted = new CountDownLatch(1);
        observeSecondReserve(reserveAttempts, secondReserveEntered, secondReserveCompleted);

        // The lock observation needs PROCESS-level visibility into the server's
        // InnoDB transaction list; the application user has no such grant, so the
        // probe runs through the container's root connection.
        var lockObserver = rootLockObserver();

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submitAndReturnStatus(expenseId, SUBMIT_KEY));
            assertThat(auditEntered.await(30, TimeUnit.SECONDS))
                    .as("first request must reach the in-transaction audit append").isTrue();

            var second = pool.submit(() -> submitAndReturnStatus(expenseId, SUBMIT_KEY));
            assertThat(secondReserveEntered.await(30, TimeUnit.SECONDS))
                    .as("the second request must reach the idempotency reserve").isTrue();
            // Verifiable blocking evidence: the second request has executed the
            // api_idempotency INSERT and the SERVER reports its transaction in
            // LOCK WAIT with that INSERT as its current statement, i.e. it is
            // waiting on the first transaction's unique-key lock (the audit latch
            // keeps the first transaction open). Bounded poll; fails the test if
            // the lock wait never appears.
            assertThat(waitForIdempotencyInsertLockWait(lockObserver))
                    .as("the second request's transaction must be observed in InnoDB "
                            + "LOCK WAIT on its api_idempotency INSERT")
                    .isTrue();
            assertThat(secondReserveCompleted.getCount())
                    .as("the second reserve must still be blocked on the first "
                            + "transaction's idempotency unique key")
                    .isEqualTo(1);
            assertThat(second.isDone()).isFalse();

            releaseAudit.countDown();
            assertThat(secondReserveCompleted.await(30, TimeUnit.SECONDS))
                    .as("the second reserve completes only after the first rolls back").isTrue();
            assertThat(second.get(60, TimeUnit.SECONDS)).isEqualTo(200);

            // The first request has no HTTP response: its unhandled
            // IllegalStateException escapes MockMvc. Assert the actual cause and
            // record it truthfully instead of claiming an HTTP 500.
            var firstFailure = catchThrowable(() -> first.get(60, TimeUnit.SECONDS));
            assertThat(firstFailure).isInstanceOf(ExecutionException.class);
            assertThat(rootCauseOf(firstFailure))
                    .as("the first request failed with the injected audit failure")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("audit unavailable");
        } finally {
            releaseAudit.countDown();
            pool.shutdownNow();
        }

        assertThat(expenseStatus(expenseId)).isEqualTo("SUBMITTED");
        assertThat(expenseVersion(expenseId)).isEqualTo(2);
        assertThat(approvalActionCount(expenseId, "SUBMIT")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_idempotency WHERE operation='EXPENSE_SUBMIT'",
                Integer.class)).isEqualTo(1);
        assertThat(auditCount("EXPENSE_SUBMITTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT response_status FROM api_idempotency WHERE operation='EXPENSE_SUBMIT'",
                Integer.class)).isEqualTo(200);
    }

    /**
     * Same convergence when the first request's transaction rolls back with a
     * 409 domain conflict: the same-key second request waits on the unique key,
     * re-reserves fresh and completes; the durable result holds exactly one
     * SUBMITTED transition and one success audit. The 409 is a real HTTP
     * response produced by the domain-exception advice.
     */
    @Test
    void sameKeySecondRequestReservesFreshAfterFirstRequestDomainConflict()
            throws Exception {
        var expenseId = commands.create(employeeUser(), createCommand(), "rb2-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));

        var auditEntered = new CountDownLatch(1);
        var releaseAudit = new CountDownLatch(1);
        var injectOnceConflict = new AtomicBoolean(true);
        doAnswer(invocation -> {
            // Only the FIRST request is sabotaged; the second request completes
            // normally after re-reserving.
            if (injectOnceConflict.compareAndSet(true, false)) {
                auditEntered.countDown();
                if (!releaseAudit.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the first request");
                }
                throw new com.aicostops.shared.web.DomainException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        com.aicostops.shared.web.ProblemCode.STATE_CONFLICT,
                        "Stale version", "The expense changed concurrently.");
            }
            return invocation.callRealMethod();
        }).when(audit).submitted(anyLong(), anyLong(), anyLong(), anyString(), anyLong());

        var reserveAttempts = new AtomicInteger();
        var secondReserveEntered = new CountDownLatch(1);
        var secondReserveCompleted = new CountDownLatch(1);
        observeSecondReserve(reserveAttempts, secondReserveEntered, secondReserveCompleted);

        var lockObserver = rootLockObserver();

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submitAndReturnStatus(expenseId, SUBMIT_KEY));
            assertThat(auditEntered.await(30, TimeUnit.SECONDS)).isTrue();

            var second = pool.submit(() -> submitAndReturnStatus(expenseId, SUBMIT_KEY));
            assertThat(secondReserveEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(waitForIdempotencyInsertLockWait(lockObserver))
                    .as("the second request's transaction must be observed in InnoDB "
                            + "LOCK WAIT on its api_idempotency INSERT")
                    .isTrue();
            assertThat(secondReserveCompleted.getCount()).isEqualTo(1);

            releaseAudit.countDown();
            assertThat(secondReserveCompleted.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(first.get(60, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(second.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            releaseAudit.countDown();
            pool.shutdownNow();
        }

        assertThat(expenseStatus(expenseId)).isEqualTo("SUBMITTED");
        assertThat(approvalActionCount(expenseId, "SUBMIT")).isEqualTo(1);
        assertThat(auditCount("EXPENSE_SUBMITTED")).isEqualTo(1);
    }

    // -- helpers ---------------------------------------------------------------

    /**
     * A server-side observation connection using the MySQL container's root
     * grant: {@code information_schema.innodb_trx} requires the PROCESS
     * privilege, which the application user deliberately does not have.
     */
    private static JdbcTemplate rootLockObserver() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl() + "?serverTimezone=UTC", "root", MYSQL.getPassword());
        return new JdbcTemplate(dataSource);
    }

    /**
     * Bounded poll of the server's InnoDB transaction list until some
     * transaction is in LOCK WAIT whose current statement is an
     * {@code INSERT INTO api_idempotency} — i.e. the second request has executed
     * its reserve INSERT and is waiting on the first transaction's unique-key
     * lock. Fails (returns false) if no such wait appears within the budget, so
     * a regression that lets the second request through immediately cannot pass.
     */
    private static boolean waitForIdempotencyInsertLockWait(JdbcTemplate observer)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var waiting = observer.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.innodb_trx
                    WHERE trx_state='LOCK WAIT'
                      AND LOWER(trx_query) LIKE 'insert into api_idempotency%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    /**
     * Spies the idempotency reserve: the second attempt counts down {@code entered}
     * before executing the reserve and {@code completed} only after it returns, so
     * a caller can prove the second request is blocked inside its reserve rather
     * than merely pending.
     */
    private void observeSecondReserve(AtomicInteger attempts, CountDownLatch entered,
            CountDownLatch completed) {
        doAnswer(invocation -> {
            var attempt = attempts.incrementAndGet();
            if (attempt == 2) {
                entered.countDown();
            }
            var result = invocation.callRealMethod();
            if (attempt == 2) {
                completed.countDown();
            }
            return result;
        }).when(idempotencyStore).reserve(
                anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    /** Performs the submit over MockMvc and returns the real HTTP status. */
    private int submitAndReturnStatus(long expenseId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses/{id}/submit", expenseId)
                        .header("Authorization", employeeBearer())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":\"1\"}"))
                .andReturn().getResponse().getStatus();
    }

    private static Throwable rootCauseOf(Throwable failure) {
        var cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private int approvalActionCount(long expenseId, String actionType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_action aa
                JOIN approval_case ac ON aa.approval_case_id=ac.id
                JOIN expense_claim ec ON ec.approval_case_id=ac.id
                WHERE ec.id=? AND aa.action_type=?
                """, Integer.class, expenseId, actionType);
    }

    private AuthenticatedUser employeeUser() {
        return new AuthenticatedUser(employeeUserId, 7);
    }

    private CreateExpenseCommand createCommand() {
        return new CreateExpenseCommand(LocalDate.parse("2026-08-01"),
                new BigDecimal("100.00000000"), "CNY");
    }
}
