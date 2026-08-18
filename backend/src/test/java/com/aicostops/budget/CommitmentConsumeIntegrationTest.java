package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.CommitmentConsumeService;
import com.aicostops.budget.application.CommitmentConsumeService.ConsumeCommand;
import com.aicostops.budget.application.CommitmentConsumeService.ConsumeResult;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AIC-045 consume primitive: an application-level financial primitive to be
 * composed inside a future ledger posting transaction (AIC-048) — never an
 * HTTP endpoint. consumed = min(entryAmount, remainingAmount); the
 * commitment's remaining, the budget's committed counter, and the append-only
 * usage lineage move together; budget.actual_amount is deliberately NOT
 * touched here (AIC-048 owns the actual-side posting); a duplicate
 * ledgerEntry lineage cannot double-consume; remaining never goes negative;
 * entry amounts beyond remaining become uncommitted actual for AIC-048.
 */
@SpringBootTest
@Tag("integration")
class CommitmentConsumeIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private CommitmentConsumeService consumeService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void partialConsumeMovesActiveToPartiallyConsumed() {
        var setup = activatedBudget("100.00000000", "30.00000000");

        var result = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("10.00000000"), 9001L)));

        assertThat(result.consumedAmount()).isEqualByComparingTo("10.00000000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("20.00000000");
        assertThat(result.status().name()).isEqualTo("PARTIALLY_CONSUMED");
        assertThat(commitmentStatus(setup.commitmentId())).isEqualTo("PARTIALLY_CONSUMED");
        assertThat(commitmentRemaining(setup.commitmentId())).isEqualTo("20.00000000");
        assertThat(commitmentVersion(setup.commitmentId())).isEqualTo(2);
        // budget.committed -= consumed exactly; actual untouched by AIC-045.
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("20.00000000");
        assertThat(budgetActual(setup.budgetId())).isEqualTo("0.00000000");
        assertThat(usageCount(setup.commitmentId())).isEqualTo(1);
        assertThat(usageAmount(setup.commitmentId(), 9001L)).isEqualTo("10.00000000");
        assertThat(auditCount("COMMITMENT_CONSUMED")).isEqualTo(1);
    }

    @Test
    void fullConsumeMovesActiveToConsumed() {
        var setup = activatedBudget("100.00000000", "30.00000000");

        var result = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("30.00000000"), 9002L)));

        assertThat(result.consumedAmount()).isEqualByComparingTo("30.00000000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("0.00000000");
        assertThat(result.status().name()).isEqualTo("CONSUMED");
        assertThat(commitmentStatus(setup.commitmentId())).isEqualTo("CONSUMED");
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("0.00000000");
        assertThat(usageAmount(setup.commitmentId(), 9002L)).isEqualTo("30.00000000");
    }

    @Test
    void entryAmountBeyondRemainingConsumesOnlyTheRemainder() {
        // The frozen example: remaining = 30, entry = 50 -> consumed = 30,
        // remaining = 0, CONSUMED, budget.committed -= 30. Never 50.
        var setup = activatedBudget("100.00000000", "30.00000000");

        var result = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("50.00000000"), 9003L)));

        assertThat(result.consumedAmount()).isEqualByComparingTo("30.00000000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("0.00000000");
        assertThat(result.status().name()).isEqualTo("CONSUMED");
        assertThat(commitmentStatus(setup.commitmentId())).isEqualTo("CONSUMED");
        assertThat(commitmentRemaining(setup.commitmentId())).isEqualTo("0.00000000");
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("0.00000000");
        assertThat(usageAmount(setup.commitmentId(), 9003L)).isEqualTo("30.00000000");
        // The uncommitted 20 stays out of the budget counters entirely here.
        assertThat(budgetActual(setup.budgetId())).isEqualTo("0.00000000");
    }

    @Test
    void partiallyConsumedCanContinueAndReachConsumed() {
        var setup = activatedBudget("100.00000000", "30.00000000");
        inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("10.00000000"), 9004L)));

        var second = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("5.00000000"), 9005L)));
        assertThat(second.consumedAmount()).isEqualByComparingTo("5.00000000");
        assertThat(second.status().name()).isEqualTo("PARTIALLY_CONSUMED");
        assertThat(commitmentRemaining(setup.commitmentId())).isEqualTo("15.00000000");
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("15.00000000");
        assertThat(usageCount(setup.commitmentId())).isEqualTo(2);

        var third = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("15.00000000"), 9006L)));
        assertThat(third.status().name()).isEqualTo("CONSUMED");
        assertThat(commitmentRemaining(setup.commitmentId())).isEqualTo("0.00000000");
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("0.00000000");
        assertThat(usageCount(setup.commitmentId())).isEqualTo(3);
    }

    @Test
    void duplicateLedgerEntryLineageCannotDoubleConsume() {
        var setup = activatedBudget("100.00000000", "30.00000000");

        var first = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("10.00000000"), 9007L)));
        // The same ledger entry (same lineage) again: idempotent replay of the
        // stored consumption, no second decrement, no second usage row.
        var second = inTransaction(() -> consumeService.consume(new ConsumeCommand(
                orgId, setup.commitmentId(), new BigDecimal("10.00000000"), 9007L)));

        assertThat(first.consumedAmount()).isEqualByComparingTo("10.00000000");
        assertThat(second.consumedAmount()).isEqualByComparingTo("10.00000000");
        assertThat(commitmentRemaining(setup.commitmentId())).isEqualTo("20.00000000");
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("20.00000000");
        assertThat(usageCount(setup.commitmentId())).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_CONSUMED")).isEqualTo(1);
    }

    @Test
    void consumeRejectsInvalidCommitmentStates() {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        var requested = insertCommitmentRow(orgId, budgetId, "REQUESTED",
                "10.00000000", null, null, 0);
        var consumed = insertCommitmentRow(orgId, budgetId, "CONSUMED",
                "10.00000000", "10.00000000", "0.00000000", 2);
        var released = insertCommitmentRow(orgId, budgetId, "RELEASED",
                "10.00000000", "10.00000000", "0.00000000", 2);
        var rejected = insertCommitmentRow(orgId, budgetId, "REJECTED",
                "10.00000000", null, null, 1);
        var canceled = insertCommitmentRow(orgId, budgetId, "CANCELED",
                "10.00000000", null, null, 1);

        for (var commitmentId : new long[] {
                requested, consumed, released, rejected, canceled }) {
            assertThatThrownBy(() -> inTransaction(() -> consumeService.consume(
                    new ConsumeCommand(orgId, commitmentId,
                            new BigDecimal("1.00000000"), 9100L + commitmentId))))
                    .isInstanceOf(DomainException.class)
                    .satisfies(problem -> {
                        var exception = (DomainException) problem;
                        assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                    });
        }
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(usageCount(requested)).isZero();
    }

    @Test
    void consumeRejectsNonPositiveOrImpreciseEntryAmounts() {
        var setup = activatedBudget("100.00000000", "30.00000000");

        assertThatThrownBy(() -> inTransaction(() -> consumeService.consume(
                new ConsumeCommand(orgId, setup.commitmentId(),
                        new BigDecimal("0.00000000"), 9201L))))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });

        assertThatThrownBy(() -> inTransaction(() -> consumeService.consume(
                new ConsumeCommand(orgId, setup.commitmentId(),
                        new BigDecimal("1.000000001"), 9202L))))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });

        // Nothing moved.
        assertThat(commitmentRemaining(setup.commitmentId())).isEqualTo("30.00000000");
        assertThat(budgetCommitted(setup.budgetId())).isEqualTo("30.00000000");
        assertThat(usageCount(setup.commitmentId())).isZero();
    }

    @Test
    void consumeRejectsWrongOrganization() {
        var foreignPeriod = insertCurrentBillingPeriod(foreignOrgId, "OPEN");
        var foreignBudget = insertBudgetRow(foreignOrgId, foreignPeriod, "ORG", foreignOrgId,
                "CNY", "1000.00000000", "0.00000000", "0.00000000");
        var foreignCommitment = insertCommitmentRow(foreignOrgId, foreignBudget, "ACTIVE",
                "10.00000000", "10.00000000", "10.00000000", 1);

        assertThatThrownBy(() -> inTransaction(() -> consumeService.consume(
                new ConsumeCommand(orgId, foreignCommitment,
                        new BigDecimal("1.00000000"), 9301L))))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void consumeRequiresOpenPeriodAndNeverTouchesActual() {
        var periodId = insertCurrentBillingPeriod(orgId, "CLOSING");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "ACTIVE",
                "10.00000000", "10.00000000", "10.00000000", 1);

        assertThatThrownBy(() -> inTransaction(() -> consumeService.consume(
                new ConsumeCommand(orgId, commitmentId,
                        new BigDecimal("1.00000000"), 9401L))))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.PERIOD_NOT_OPEN);
                });

        assertThat(commitmentStatus(commitmentId)).isEqualTo("ACTIVE");
        assertThat(commitmentRemaining(commitmentId)).isEqualTo("10.00000000");
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
        assertThat(usageCount(commitmentId)).isZero();
    }

    // -- helpers --------------------------------------------------------------

    private Activated activatedBudget(String total, String remaining) {
        var periodId = insertCurrentBillingPeriod(orgId, "OPEN");
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                total, "0.00000000", "0.00000000");
        var commitmentId = insertCommitmentRow(orgId, budgetId, "ACTIVE",
                remaining, remaining, remaining, 1);
        insertCommitmentCase(orgId, commitmentId, "APPROVED");
        jdbc.update("UPDATE budget SET committed_amount=? WHERE id=?",
                new BigDecimal(remaining), budgetId);
        return new Activated(commitmentId, budgetId);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private record Activated(long commitmentId, long budgetId) {
    }
}
