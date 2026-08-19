package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.ReleaseCommitmentCommand;
import com.aicostops.budget.application.CommitmentConsumeService;
import com.aicostops.budget.application.CommitmentConsumeService.ConsumeCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Blocking-fix evidence: a commitment transaction is bound to the budget's
 * own billing period — the frozen rules only lock that period and require
 * OPEN (03-state-machines.md, 04-transactions-idempotency-concurrency.md
 * §8/§9/§10). No frozen rule re-checks whether the wall clock still falls
 * inside {@code [period_start, period_end)}: an OPEN period whose window has
 * already passed must still accept late financial operations on commitments
 * whose Source/Allocation already determined that period. In particular the
 * AIC-045 consume primitive must never re-gate AIC-048 posting entries with
 * "today's date" over the posting period the Source already fixed.
 */
@SpringBootTest
@Tag("integration")
class CommitmentOpenPeriodSemanticsIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;
    @Autowired
    private CommitmentConsumeService consumeService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void consumeAllowsAnOpenHistoricalPeriod() {
        var periodId = insertPastOpenBillingPeriod(orgId);
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "30.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "ACTIVE",
                "30.00000000", "30.00000000", "30.00000000", 1);
        insertCommitmentCase(orgId, commitmentId, "APPROVED");
        insertSyntheticLedgerEntry(9701L, periodId);

        var result = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, commitmentId, new BigDecimal("10.00000000"), 9701L)));

        assertThat(result.consumedAmount()).isEqualByComparingTo("10.00000000");
        assertThat(result.status().name()).isEqualTo("PARTIALLY_CONSUMED");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("PARTIALLY_CONSUMED");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("20.00000000");
        assertThat(budgetCommitted(budgetId)).isEqualTo("20.00000000");
        assertThat(usageCount(commitmentId)).isEqualTo(1);
    }

    @Test
    void approveAllowsAnOpenHistoricalPeriod() {
        var periodId = insertPastOpenBillingPeriod(orgId);
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertRequested(orgId, budgetId, "50.00000000");

        var detail = commands.approve(reviewerUser(), commitmentId,
                new ApproveCommitmentCommand(0), "hist-appr-1");

        assertThat(detail.status().name()).isEqualTo("ACTIVE");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(budgetCommitted(budgetId)).isEqualTo("50.00000000");
        assertThat(approvalCaseStatus(commitmentId)).isEqualTo("APPROVED");
    }

    @Test
    void releaseAllowsAnOpenHistoricalPeriod() {
        var periodId = insertPastOpenBillingPeriod(orgId);
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "30.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "PARTIALLY_CONSUMED",
                "50.00000000", "50.00000000", "30.00000000", 2);
        insertCommitmentCase(orgId, commitmentId, "APPROVED");

        var detail = commands.release(reviewerUser(), commitmentId,
                new ReleaseCommitmentCommand(2), "hist-rel-1");

        assertThat(detail.status().name()).isEqualTo("RELEASED");
        assertThat(commitmentStatus(commitmentId)).isEqualTo("RELEASED");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("0.00000000");
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
    }

    // -- fixtures --------------------------------------------------------------

    /**
     * An OPEN billing period whose window lies entirely in the past relative
     * to the live clock: {@code covers(now)} is guaranteed false, while the
     * status stays OPEN. The frozen commitment rules gate on the status only.
     */
    private long insertPastOpenBillingPeriod(long org) {
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
        var now = LocalDateTime.now(Clock.systemUTC());
        var start = now.minusDays(60).format(formatter);
        var end = now.minusDays(30).format(formatter);
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,?,?,'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, start, end);
        return jdbc.queryForObject("""
                SELECT id FROM billing_period
                WHERE org_id=? AND period_start=? AND period_end=?
                """, Long.class, org, start, end);
    }

    private long insertRequested(long org, long budgetId, String requested) {
        var commitmentId = insertCommitmentRow(org, budgetId, "REQUESTED", requested,
                null, null, 0);
        var caseId = insertCommitmentCase(org, commitmentId, "PENDING");
        insertApprovalActionRow(org, caseId, requesterMemberId, "SUBMIT", "NONE", "REQUESTED");
        return commitmentId;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }
}
