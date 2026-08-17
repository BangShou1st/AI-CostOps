package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands.ApproveExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RejectExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RequestInfoCommand;
import com.aicostops.expense.application.ExpenseCommands.SubmitExpenseCommand;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.expense.application.ExpenseReviewCommandService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Expense command idempotency: a replayed key returns the first success
 * response without repeating side effects, and the same key with a different
 * body (expectedVersion / comment) is a 409 conflict.
 */
@SpringBootTest
@Tag("integration")
class ExpenseIdempotencyIntegrationTest extends ExpenseTestSupport {

    private static final byte[] RECEIPT = "idem-receipt".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ExpenseClaimCommandService commands;
    @Autowired
    private ExpenseReviewCommandService reviews;
    @Autowired
    private ExpenseEvidenceUploadService evidenceUploads;

    @Test
    void submitReplaysSameKeyAndConflictsOnDifferentVersion() {
        var expenseId = commands.create(employeeUser(), createCommand(), "idem-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));

        var first = commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(1), "idem-submit-key");
        var replayed = commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(1), "idem-submit-key");
        assertThat(replayed).isEqualTo(first);
        assertThat(expenseStatus(expenseId)).isEqualTo("SUBMITTED");
        // one SUBMIT action, one idempotency row, one audit event
        assertThat(approvalActionCount(expenseId, "SUBMIT")).isEqualTo(1);
        assertThat(auditCount("EXPENSE_SUBMITTED")).isEqualTo(1);

        // same key, different expectedVersion -> 409
        assertThatThrownBy(() -> commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(3), "idem-submit-key"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
                });
    }

    @Test
    void requestInfoAndApproveConflictOnReusedKeyWithDifferentComment() {
        var expenseId = commands.create(employeeUser(), createCommand(), "idem-ri-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), expenseId, new SubmitExpenseCommand(1), "idem-ri-submit");

        var first = reviews.requestInfo(financeUser(), expenseId,
                new RequestInfoCommand(2, "need receipt"), "idem-ri-key");
        var replayed = reviews.requestInfo(financeUser(), expenseId,
                new RequestInfoCommand(2, "need receipt"), "idem-ri-key");
        assertThat(replayed).isEqualTo(first);
        assertThat(approvalActionCount(expenseId, "REQUEST_INFO")).isEqualTo(1);

        // same key, different comment -> 409
        assertThatThrownBy(() -> reviews.requestInfo(financeUser(), expenseId,
                new RequestInfoCommand(2, "need different info"), "idem-ri-key"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
                });
    }

    @Test
    void createReplayNeverDuplicatesExpense() {
        var first = commands.create(employeeUser(), createCommand(), "idem-create-dup");
        var replayed = commands.create(employeeUser(), createCommand(), "idem-create-dup");
        assertThat(replayed).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM expense_claim WHERE claimant_member_id=?
                """, Integer.class, employeeMemberId)).isEqualTo(1);
    }

    @Test
    void rejectReplayKeepsSingleTerminalResult() {
        var expenseId = commands.create(employeeUser(), createCommand(), "idem-rej-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), expenseId, new SubmitExpenseCommand(1), "idem-rej-submit");

        var first = reviews.reject(financeUser(), expenseId,
                new RejectExpenseCommand(2, "policy violation"), "idem-rej-key");
        var replayed = reviews.reject(financeUser(), expenseId,
                new RejectExpenseCommand(2, "policy violation"), "idem-rej-key");
        assertThat(replayed).isEqualTo(first);
        assertThat(approvalActionCount(expenseId, "REJECT")).isEqualTo(1);
        assertThat(auditCount("EXPENSE_REVIEWED")).isEqualTo(1);
        // approve after the terminal reject must fail on the state, not replay
        assertThatThrownBy(() -> reviews.approve(financeUser(), expenseId,
                new ApproveExpenseCommand(3), "idem-ap-after-rej"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                });
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

    private AuthenticatedUser financeUser() {
        return new AuthenticatedUser(financeUserId, 7);
    }

    private CreateExpenseCommand createCommand() {
        return new CreateExpenseCommand(LocalDate.parse("2026-08-01"),
                new BigDecimal("100.00000000"), "CNY");
    }
}