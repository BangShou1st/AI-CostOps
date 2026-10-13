package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.aicostops.expense.application.ExpenseAuditPort;
import com.aicostops.expense.application.ExpenseEvidenceDownloadService;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Evidence upload/binding failure recovery for the reimbursement chain.
 *
 * <p>Storage bytes are written outside the expense lock, so a bind failure
 * cannot be undone by the database transaction. These tests pin the documented
 * behaviour: the object is retained (never deleted, because an identical
 * {@code (org, sha256)} row may be shared by another claim), an identical
 * re-upload reuses the same evidence row, an audit failure rolls back only the
 * binding, concurrent duplicate uploads bind exactly once, and evidence is never
 * readable across organizations.
 */
@SpringBootTest
@Tag("integration")
class ExpenseEvidenceBindingRecoveryIntegrationTest extends ExpenseTestSupport {

    private static final byte[] RECEIPT = "recovery-receipt".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ExpenseEvidenceUploadService uploads;
    @Autowired
    private ExpenseEvidenceDownloadService downloads;
    @MockitoSpyBean
    private ExpenseAuditPort audit;

    /**
     * The evidence is staged and stored successfully, but the audited attach
     * fails inside its transaction. The binding (version + evidence pointer) must
     * roll back, while the already-written object and its AVAILABLE row stay:
     * object storage effects are not undone by a database rollback.
     */
    @Test
    void auditFailureOnAttachRollsBackTheBindingButKeepsTheStoredEvidence() {
        var expenseId = insertExpenseDraft();
        var sha = sha256Of(RECEIPT);
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .evidenceAttached(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());

        assertThatThrownBy(() -> uploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT)))
                .hasMessageContaining("audit unavailable");

        assertThat(expenseVersion(expenseId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT evidence_id FROM expense_claim WHERE id=?", Long.class, expenseId)).isNull();
        assertThat(auditCount("EXPENSE_EVIDENCE_ATTACHED")).isZero();
        // the stored object row is retained and AVAILABLE
        assertThat(countEvidenceBySha(sha)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT storage_status FROM evidence WHERE org_id=? AND sha256=?",
                String.class, orgId, sha)).isEqualTo("AVAILABLE");
    }

    /**
     * Re-uploading identical bytes reuses the existing {@code (org, sha256)}
     * row instead of creating a second one; the claim can be re-bound to the
     * same evidence after a previous failed attempt.
     */
    @Test
    void repeatedUploadOfIdenticalBytesReusesOneEvidenceRow() {
        var expenseId = insertExpenseDraft();
        var sha = sha256Of(RECEIPT);

        var first = uploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.evidenceId()).isNotNull();

        var second = uploads.attach(employeeUser(), expenseId, 1,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.evidenceId()).isEqualTo(first.evidenceId());
        assertThat(countEvidenceBySha(sha)).isEqualTo(1);
    }

    /**
     * Two concurrent uploads of the same bytes against the same expectedVersion:
     * exactly one binds the claim, the other is a 409, and the shared evidence
     * row is created only once.
     */
    @Test
    void concurrentDuplicateUploadBindsOnceAndKeepsOneEvidenceRow() throws Exception {
        var expenseId = insertExpenseDraft();
        var sha = sha256Of(RECEIPT);

        var outcomes = runConcurrently(
                () -> uploads.attach(employeeUser(), expenseId, 0,
                        "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT)),
                () -> uploads.attach(employeeUser(), expenseId, 0,
                        "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT)));

        assertThat(outcomes.successCount()).isEqualTo(1);
        assertThat(outcomes.conflictCount()).isEqualTo(1);
        assertThat(expenseVersion(expenseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT evidence_id FROM expense_claim WHERE id=?", Long.class, expenseId)).isNotNull();
        assertThat(countEvidenceBySha(sha)).isEqualTo(1);
        assertThat(auditCount("EXPENSE_EVIDENCE_ATTACHED")).isEqualTo(1);
    }

    /** Evidence of another organization is never readable, by owner or reviewer. */
    @Test
    void crossOrganizationEvidenceDownloadIsNotFound() {
        var foreignUserId = insertUser("ev-cross-" + System.nanoTime() + "@example.com");
        var foreignMemberId = insertMember(foreignOrgId, foreignUserId);
        var foreignExpenseId = insertExpenseDraftFor(foreignOrgId, foreignMemberId,
                "50.00000000", "CNY", "DRAFT");
        var evidenceId = insertEvidence(foreignOrgId, foreignMemberId,
                sha256Of("foreign-evidence".getBytes(StandardCharsets.UTF_8)));
        jdbc.update("UPDATE expense_claim SET evidence_id=? WHERE id=?",
                evidenceId, foreignExpenseId);

        // our employee asks for a foreign expense's evidence (OWN path)
        assertThatThrownBy(() -> downloads.download(employeeUser(), foreignExpenseId))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status().value()).isEqualTo(404));
        // our finance actor asks via the ORG review path
        assertThatThrownBy(() -> downloads.download(financeUser(), foreignExpenseId))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status().value()).isEqualTo(404));
    }

    private long countEvidenceBySha(String sha) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence WHERE org_id=? AND sha256=?",
                Long.class, orgId, sha);
    }

    private static String sha256Of(byte[] bytes) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
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
}
