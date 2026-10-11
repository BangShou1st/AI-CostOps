package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

import com.aicostops.expense.application.ExpenseAuditPort;
import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.expense.application.ExpenseReviewCommandService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** An injected audit failure occurs after business writes, against real MySQL transactions. */
@SpringBootTest
@Tag("integration")
class ExpenseFailureAtomicityIntegrationTest extends ExpenseTestSupport {
    @Autowired private ExpenseClaimCommandService commands;
    @Autowired private ExpenseReviewCommandService reviews;
    @Autowired private ExpenseEvidenceUploadService uploads;
    @MockitoSpyBean private ExpenseAuditPort audit;

    @Test
    void failedCreateRollsBackTheClaimAndIdempotencyReservation() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .claimCreated(anyLong(), anyLong(), anyLong(), anyString());
        assertThatThrownBy(() -> commands.create(employee(), createCommand(), "failed-create"))
                .hasMessageContaining("audit unavailable");
        assertThat(count("expense_claim")).isZero();
        assertThat(count("api_idempotency")).isZero();
        assertThat(count("audit_event")).isZero();
    }

    @Test
    void failedSubmitRollsBackTheApprovalHistoryStatusAndIdempotency() {
        var id = draftWithEvidence();
        var idempotencyBefore = count("api_idempotency");
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .submitted(anyLong(), anyLong(), anyLong(), anyString(), anyLong());
        assertThatThrownBy(() -> commands.submit(employee(), id, new ExpenseCommands.SubmitExpenseCommand(1), "failed-submit"))
                .hasMessageContaining("audit unavailable");
        assertThat(expenseStatus(id)).isEqualTo("DRAFT");
        assertThat(expenseVersion(id)).isEqualTo(1);
        assertThat(count("approval_case")).isZero();
        assertThat(count("approval_action")).isZero();
        assertThat(count("api_idempotency")).isEqualTo(idempotencyBefore);
    }

    @Test
    void failedApprovalRollsBackBothStatusesHistoryAndSavedSuccessResponse() {
        var id = submitted();
        var idempotencyBefore = count("api_idempotency");
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .reviewed(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), isNull());
        assertThatThrownBy(() -> reviews.approve(finance(), id, new ExpenseCommands.ApproveExpenseCommand(2), "failed-approve"))
                .hasMessageContaining("audit unavailable");
        assertPending(id, idempotencyBefore);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CLOSING", "CLOSED"})
    void approvalRequiresOpenPeriodAndLeavesSubmittedClaimUnchanged(String status) {
        var id = submitted();
        var idempotencyBefore = count("api_idempotency");
        jdbc.update("UPDATE billing_period SET status=? WHERE org_id=?", status, orgId);
        assertThatThrownBy(() -> reviews.approve(finance(), id, new ExpenseCommands.ApproveExpenseCommand(2), "period-approve"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code().name()).isEqualTo("PERIOD_NOT_OPEN"));
        assertPending(id, idempotencyBefore);
    }

    private void assertPending(long id, long idempotencyBefore) {
        assertThat(expenseStatus(id)).isEqualTo("SUBMITTED");
        assertThat(expenseVersion(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM approval_case WHERE expense_claim_id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(count("approval_action")).isEqualTo(1);
        assertThat(count("api_idempotency")).isEqualTo(idempotencyBefore);
        assertThat(auditCount("EXPENSE_REVIEWED")).isZero();
    }

    private long submitted() {
        var id = draftWithEvidence();
        commands.submit(employee(), id, new ExpenseCommands.SubmitExpenseCommand(1), "submit-fixture");
        return id;
    }

    private long draftWithEvidence() {
        var id = commands.create(employee(), createCommand(), "create-fixture").id();
        uploads.attach(employee(), id, 0, "receipt.pdf", "application/pdf", new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return id;
    }

    private ExpenseCommands.CreateExpenseCommand createCommand() {
        return new ExpenseCommands.CreateExpenseCommand(LocalDate.parse("2026-08-02"), new BigDecimal("100.00000000"), "CNY");
    }
    private AuthenticatedUser employee() { return new AuthenticatedUser(employeeUserId, 7); }
    private AuthenticatedUser finance() { return new AuthenticatedUser(financeUserId, 7); }
    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE org_id=?", Long.class, orgId);
    }
}
