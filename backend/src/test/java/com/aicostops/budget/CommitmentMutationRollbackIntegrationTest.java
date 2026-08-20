package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.ReleaseCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.budget.application.CommitmentAuditPort;
import com.aicostops.budget.application.CommitmentConsumeService;
import com.aicostops.budget.application.CommitmentConsumeService.ConsumeCommand;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Post-mutation rollback evidence (review Blocking 4): each commitment
 * command performs its budget/commitment/approval mutations and only then
 * appends the audit event — when that audit write fails, the WHOLE command
 * transaction must roll back to the pre-command state. This is what proves
 * the future AIC-048 ledger posting transaction can compose the consume
 * primitive and never leave half Ledger/Budget state behind.
 *
 * <p>The audit port is the only mocked seam; everything else runs against the
 * real MySQL Testcontainer. For consume, the primitive runs inside an outer
 * TransactionTemplate (its MANDATORY contract), so the rollback evidence is
 * the outer transaction's, exactly like AIC-048 will provide.
 */
@SpringBootTest
@Tag("integration")
class CommitmentMutationRollbackIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;
    @Autowired
    private CommitmentConsumeService consumeService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private CommitmentAuditPort auditPort;

    @Test
    void activationRollsBackEverythingWhenAuditWriteFails() {
        // A current OPEN period: the RED phase must exercise the real
        // audit-failure path, not the historical-period gate.
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        // Real request flow: REQUESTED + PENDING case + SUBMIT action.
        var requested = commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("30.00000000"), "CNY"),
                "act-rollback-req");
        var commitmentId = requested.id();
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");

        // The audit write fails after the counter, the commitment, the
        // approval case and the APPROVE action have all executed in-transaction.
        doThrow(new IllegalStateException("simulated audit outage"))
                .when(auditPort).activated(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), anyLong(), any(), any());

        assertThatThrownBy(() -> commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "act-rollback-appr"))
                .isInstanceOf(IllegalStateException.class);

        // Budget counter and version back to the originals.
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(budgetVersion(budgetId)).isZero();
        // Commitment back to REQUESTED with no approved/remaining and the
        // original version.
        assertThat(commitmentStatus(commitmentId)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject(
                "SELECT approved_amount FROM budget_commitment WHERE id=?",
                BigDecimal.class, commitmentId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?",
                BigDecimal.class, commitmentId)).isNull();
        assertThat(commitmentVersion(commitmentId)).isZero();
        // Approval case back to PENDING; only the original SUBMIT survives.
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("PENDING");
        assertThat(approvalActionCount(commitmentId)).isEqualTo(1);
        // No audit, no provisional idempotency row for the approve command.
        assertThat(auditCount("COMMITMENT_ACTIVATED")).isZero();
        assertThat(idempotencyProvisionalCount("COMMITMENT_APPROVE")).isZero();
    }

    @Test
    void releaseRollsBackEverythingWhenAuditWriteFails() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "50.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "ACTIVE",
                "50.00000000", "50.00000000", "50.00000000", 1);
        insertCommitmentCase(orgId, commitmentId, "APPROVED");

        doThrow(new IllegalStateException("simulated audit outage"))
                .when(auditPort).released(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), anyLong(), any(), any());

        assertThatThrownBy(() -> commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(1), "rel-rollback-1"))
                .isInstanceOf(IllegalStateException.class);

        // The committed decrement and its version bump rolled back.
        assertThat(budgetCommitted(budgetId)).isEqualTo("50.00000000");
        assertThat(budgetVersion(budgetId)).isZero();
        // The commitment stays ACTIVE with the full remainder and version.
        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("50.00000000");
        assertThat(commitmentVersion(commitmentId)).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_RELEASED")).isZero();
        assertThat(idempotencyProvisionalCount("COMMITMENT_RELEASE")).isZero();
    }

    @Test
    void consumeRollsBackEverythingWhenAuditWriteFails() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "100.00000000", "0.00000000", "30.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "ACTIVE",
                "30.00000000", "30.00000000", "30.00000000", 1);
        insertCommitmentCase(orgId, commitmentId, "APPROVED");
        insertSyntheticLedgerEntry(9801L, periodId);

        doThrow(new IllegalStateException("simulated audit outage"))
                .when(auditPort).consumed(anyLong(), isNull(), anyLong(), anyLong(),
                any(), anyLong(), any(), any());

        assertThatThrownBy(() -> inTransaction(() -> consumeService.consume(
                new ConsumeCommand(orgId, commitmentId,
                        new BigDecimal("10.00000000"), 9801L))))
                .isInstanceOf(IllegalStateException.class);

        // The outer transaction rolled the consume primitive back entirely:
        // no usage row, budget counters and commitment restored as if the
        // AIC-048 outer posting transaction never ran.
        assertThat(usageCount(commitmentId)).isZero();
        assertThat(budgetCommitted(budgetId)).isEqualTo("30.00000000");
        assertThat(budgetVersion(budgetId)).isZero();
        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("30.00000000");
        assertThat(commitmentVersion(commitmentId)).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_CONSUMED")).isZero();
    }

    // -- helpers --------------------------------------------------------------

    private int idempotencyProvisionalCount(String operation) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM api_idempotency
                WHERE org_id=? AND operation=? AND response_status=0
                """, Integer.class, orgId, operation);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }
}
