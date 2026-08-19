package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands.ApproveExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.CancelExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.EditExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RejectExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RequestInfoCommand;
import com.aicostops.expense.application.ExpenseCommands.SubmitExpenseCommand;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.expense.application.ExpenseQueryService;
import com.aicostops.expense.application.ExpenseReviewCommandService;
import com.aicostops.expense.application.ExpenseReviewQueryService;
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
 * Full M4 expense lifecycle at the service boundary: create DRAFT -> attach
 * evidence -> submit -> request-info -> edit + resubmit -> approve/posted, plus the
 * cancel path and the finance queue behavior (approved-unallocated visible).
 * Expense allocation confirm is covered by the allocation phase tests.
 */
@SpringBootTest
@Tag("integration")
class ExpenseLifecycleIntegrationTest extends ExpenseTestSupport {

    private static final byte[] RECEIPT = "lifecycle-receipt".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ExpenseClaimCommandService commands;
    @Autowired
    private ExpenseReviewCommandService reviews;
    @Autowired
    private ExpenseEvidenceUploadService evidenceUploads;
    @Autowired
    private ExpenseQueryService queries;
    @Autowired
    private ExpenseReviewQueryService reviewQueries;

    @Test
    void fullLifecycleCreateSubmitRequestInfoResubmitApprove() {
        var expenseId = commands.create(employeeUser(), createCommand(), "lc-create-1").id();

        // submit without evidence is rejected
        assertThatThrownBy(() -> commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(0), "lc-submit-no-evidence"))
                .satisfies(this::assertConflict);

        var withEvidence = evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        assertThat(withEvidence.version()).isEqualTo(1);

        var submitted = commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(1), "lc-submit");
        assertThat(submitted.status().name()).isEqualTo("SUBMITTED");
        assertThat(submitted.approvalCaseId()).isNotNull();
        assertThat(submitted.version()).isEqualTo(2);
        assertThat(submitted.history()).hasSize(1);
        assertThat(submitted.history().get(0).actionType().name()).isEqualTo("SUBMIT");
        assertThat(auditCount("EXPENSE_SUBMITTED")).isEqualTo(1);

        var needsInfo = reviews.requestInfo(financeUser(), expenseId,
                new RequestInfoCommand(2, "Please attach the original receipt"), "lc-request-info");
        assertThat(needsInfo.status().name()).isEqualTo("NEEDS_INFO");
        assertThat(needsInfo.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "REQUEST_INFO");

        var edited = commands.edit(employeeUser(), expenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-02"),
                        new BigDecimal("105.00000000"), "CNY", 3));
        assertThat(edited.version()).isEqualTo(4);

        var resubmitted = commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(4), "lc-resubmit");
        assertThat(resubmitted.status().name()).isEqualTo("SUBMITTED");
        assertThat(resubmitted.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "REQUEST_INFO", "RESUBMIT");
        assertThat(auditCount("EXPENSE_SUBMITTED")).isEqualTo(2);

        var approved = reviews.approve(financeUser(), expenseId,
                new ApproveExpenseCommand(5), "lc-approve");
        assertThat(approved.status().name()).isEqualTo("APPROVED");
        assertThat(approved.approvalStatus().name()).isEqualTo("APPROVED");
        assertThat(approved.postingReady()).isFalse();
        assertThat(approved.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "REQUEST_INFO", "RESUBMIT", "APPROVE");
        assertThat(expenseStatus(expenseId)).isEqualTo("APPROVED");
        assertThat(auditCount("EXPENSE_REVIEWED")).isEqualTo(2);

        // APPROVED is terminal for the claimant: no edit, no cancel
        assertThatThrownBy(() -> commands.edit(employeeUser(), expenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-03"),
                        new BigDecimal("110.00000000"), "CNY", 6)))
                .satisfies(this::assertConflict);
        assertThatThrownBy(() -> commands.cancel(employeeUser(), expenseId,
                new CancelExpenseCommand(6), "lc-cancel-approved"))
                .satisfies(this::assertConflict);
    }

    @Test
    void requestInfoThenRejectIsTerminal() {
        var expenseId = commands.create(employeeUser(), createCommand(), "lc-rej-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), expenseId, new SubmitExpenseCommand(1), "lc-rej-submit");

        var rejected = reviews.reject(financeUser(), expenseId,
                new RejectExpenseCommand(2, "Not an approved expense category"), "lc-reject");
        assertThat(rejected.status().name()).isEqualTo("REJECTED");
        assertThat(rejected.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "REJECT");

        // a rejected expense cannot be resubmitted
        assertThatThrownBy(() -> commands.submit(employeeUser(), expenseId,
                new SubmitExpenseCommand(3), "lc-resubmit-rejected"))
                .satisfies(this::assertConflict);
    }

    @Test
    void cancelOnlyWorksOnSubmitted() {
        // DRAFT cannot be canceled directly
        var draftId = commands.create(employeeUser(), createCommand(), "lc-cancel-draft").id();
        assertThatThrownBy(() -> commands.cancel(employeeUser(), draftId,
                new CancelExpenseCommand(0), "lc-cancel-draft-key"))
                .satisfies(this::assertConflict);

        var expenseId = commands.create(employeeUser(), createCommand(), "lc-cancel-create").id();
        evidenceUploads.attach(employeeUser(), expenseId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), expenseId, new SubmitExpenseCommand(1), "lc-cancel-submit");

        var canceled = commands.cancel(employeeUser(), expenseId,
                new CancelExpenseCommand(2), "lc-cancel");
        assertThat(canceled.status().name()).isEqualTo("CANCELED");
        assertThat(canceled.approvalStatus().name()).isEqualTo("CANCELED");
        assertThat(canceled.history()).extracting(a -> a.actionType().name())
                .containsExactly("SUBMIT", "CANCEL");
        assertThat(expenseStatus(expenseId)).isEqualTo("CANCELED");
        assertThat(auditCount("EXPENSE_CANCELED")).isEqualTo(1);
    }

    @Test
    void reviewQueueShowsSubmittedNeedsInfoAndApprovedUnallocated() {
        // draft is invisible
        var draftId = commands.create(employeeUser(), createCommand(), "lc-q-draft").id();
        assertThat(reviewQueries.countQueue(financeUser(), "ALL")).isZero();

        var submittedId = commands.create(employeeUser(), createCommand(), "lc-q-submit").id();
        evidenceUploads.attach(employeeUser(), submittedId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), submittedId, new SubmitExpenseCommand(1), "lc-q-submit-key");

        var needsInfoId = commands.create(employeeUser(), createCommand(), "lc-q-ri").id();
        evidenceUploads.attach(employeeUser(), needsInfoId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), needsInfoId, new SubmitExpenseCommand(1), "lc-q-ri-submit");
        reviews.requestInfo(financeUser(), needsInfoId, new RequestInfoCommand(2, "more info"), "lc-q-ri");

        var approvedId = commands.create(employeeUser(), createCommand(), "lc-q-approve").id();
        evidenceUploads.attach(employeeUser(), approvedId, 0,
                "receipt.pdf", "application/pdf", new ByteArrayInputStream(RECEIPT));
        commands.submit(employeeUser(), approvedId, new SubmitExpenseCommand(1), "lc-q-ap-submit");
        reviews.approve(financeUser(), approvedId, new ApproveExpenseCommand(2), "lc-q-ap");

        assertThat(draftId).isPositive();
        assertThat(reviewQueries.countQueue(financeUser(), "ALL")).isEqualTo(3);
        assertThat(reviewQueries.listQueue(financeUser(), "ALL", 0, 50))
                .extracting(d -> d.status().name())
                .containsExactlyInAnyOrder("SUBMITTED", "NEEDS_INFO", "APPROVED");
        assertThat(reviewQueries.countQueue(financeUser(), "APPROVED")).isEqualTo(1);
        assertThat(reviewQueries.countQueue(financeUser(), "SUBMITTED")).isEqualTo(1);

        // POSTED expenses leave the active review queue, even when the queue
        // entry had already reached APPROVED.
        setExpenseStatus(approvedId, "POSTED");
        assertThat(reviewQueries.countQueue(financeUser(), "ALL")).isEqualTo(2);
        assertThat(reviewQueries.countQueue(financeUser(), "APPROVED")).isZero();

        // finance can read any same-org detail without owner comparison
        var viaReview = reviewQueries.getForReview(financeUser(), needsInfoId);
        assertThat(viaReview.status().name()).isEqualTo("NEEDS_INFO");
        // the owner still sees history via their own view
        var owned = queries.getOwned(employeeUser(), approvedId);
        assertThat(owned.status().name()).isEqualTo("APPROVED");
    }

    private void assertConflict(Throwable thrown) {
        var domain = (DomainException) thrown;
        assertThat(domain.status().value()).isEqualTo(409);
        assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
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
