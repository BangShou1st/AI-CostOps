package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.allocation.infrastructure.AllocationPostingAdapter;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationDecisionStatus;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.attribution.infrastructure.AllocationDecisionMapper;
import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.budget.application.LedgerBudgetPort.EntryScopeAmount;
import com.aicostops.budget.application.LedgerBudgetService;
import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.BudgetCommitmentStatus;
import com.aicostops.budget.domain.BudgetStatus;
import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.cost.application.ChargePostingPort.ChargePostingSource;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.infrastructure.ChargePostingAdapter;
import com.aicostops.cost.infrastructure.ChargePostingMapper;
import com.aicostops.expense.application.ExpensePostingService;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.domain.ExpenseClaimStatus;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Focused contract tests for the four owner-module posting seams. */
class PostingPortIntegrationTest {

    private static final Instant EFFECTIVE_AT = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void chargeRequiresConfirmedCleanAndMatchingDecisionPointer() {
        var mapper = mock(ChargePostingMapper.class);
        var source = new ChargePostingSource(11L, money("12.00000000"), "USD", EFFECTIVE_AT,
                71L, ReviewStatus.CLEAN, true);
        when(mapper.selectForPostingForUpdate(9L, 11L)).thenReturn(source);

        var result = new ChargePostingAdapter(mapper)
                .lockAndRequirePostable(9L, 11L, 71L);

        assertThat(result).isEqualTo(source);
        verify(mapper).selectForPostingForUpdate(9L, 11L);
        when(mapper.selectForPostingForUpdate(9L, 11L)).thenReturn(
                new ChargePostingSource(11L, source.amount(), source.currency(), EFFECTIVE_AT,
                        70L, ReviewStatus.CLEAN, true));
        assertThatThrownBy(() -> new ChargePostingAdapter(mapper)
                .lockAndRequirePostable(9L, 11L, 71L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("allocation pointer");
    }

    @Test
    void allocationRequiresConfirmedDecisionSubjectAndKeepsLineOrder() {
        var mapper = mock(AllocationDecisionMapper.class);
        var decision = decision(71L, AllocationSubjectType.CHARGE_FACT, 11L);
        var first = line(100L, 0, "7.00000000");
        var second = line(101L, 1, "5.00000000");
        when(mapper.selectByIdForUpdate(9L, 71L)).thenReturn(decision);
        when(mapper.selectLinesOfDecisionForUpdate(9L, 71L)).thenReturn(List.of(first, second));

        var allocation = new AllocationPostingAdapter(mapper).lockConfirmed(
                9L, 71L, AllocationSubjectType.CHARGE_FACT, 11L);

        assertThat(allocation.lines()).containsExactly(first, second);
        assertThat(allocation.decision()).isEqualTo(decision);
    }

    @Test
    void expenseRequiresApprovedAndPointerAndUsesPostedCas() {
        var mapper = mock(ExpenseClaimMapper.class);
        var claim = new ExpenseClaim(22L, 9L, 31L, null, LocalDate.of(2026, 8, 19),
                money("12.00000000"), "USD", ExpenseClaimStatus.APPROVED, 71L, null, 4L,
                EFFECTIVE_AT, EFFECTIVE_AT);
        when(mapper.selectByIdForUpdate(9L, 22L)).thenReturn(claim);
        when(mapper.markPosted(9L, 22L, 4L, EFFECTIVE_AT)).thenReturn(1);

        var service = new ExpensePostingService(mapper);
        assertThat(service.lockAndRequireApproved(9L, 22L, 71L).version()).isEqualTo(4L);
        service.markPosted(9L, 22L, 4L, EFFECTIVE_AT);

        verify(mapper).markPosted(9L, 22L, 4L, EFFECTIVE_AT);
    }

    @Test
    void budgetUsesExactBeforeOrgFallbackAndLocksIdsInOrder() {
        var periodFence = mock(BillingPeriodFinancialWriteFence.class);
        var budgets = mock(BudgetMapper.class);
        var commitments = mock(BudgetCommitmentMapper.class);
        var period = new BillingPeriod(5L, 9L, EFFECTIVE_AT.minusSeconds(1),
                EFFECTIVE_AT.plusSeconds(3600), BillingPeriodStatus.OPEN, 0L, null, null, null,
                0L, EFFECTIVE_AT, EFFECTIVE_AT);
        var exact = budget(20L, 5L, "PROJECT", 33L, "USD");
        var org = budget(21L, 5L, "ORG", 9L, "USD");
        when(periodFence.lockOpenAt(9L, EFFECTIVE_AT)).thenReturn(period);
        when(budgets.selectByIdentity(9L, 5L, "PROJECT", 33L, "USD")).thenReturn(exact);
        when(budgets.selectByIdentity(9L, 5L, "PROJECT", 34L, "USD")).thenReturn(null);
        when(budgets.selectByIdentity(9L, 5L, "ORG", 9L, "USD")).thenReturn(org);
        when(budgets.selectByIdForUpdate(9L, 20L)).thenReturn(exact);
        when(budgets.selectByIdForUpdate(9L, 21L)).thenReturn(org);
        when(commitments.selectByIdForUpdate(9L, 30L)).thenReturn(commitment(30L, 20L));

        var service = new LedgerBudgetService(periodFence, budgets, commitments);
        assertThat(service.lockOpenPeriodAt(9L, EFFECTIVE_AT)).isEqualTo(period);
        assertThat(service.resolveSelections(9L, 5L, List.of(
                new EntryScopeAmount(0, com.aicostops.iam.domain.ScopeType.PROJECT, 33L, "USD"),
                new EntryScopeAmount(1, com.aicostops.iam.domain.ScopeType.PROJECT, 34L, "USD")))
                .get(0).budget()).isEqualTo(exact);
        assertThat(service.resolveSelections(9L, 5L, List.of(
                new EntryScopeAmount(1, com.aicostops.iam.domain.ScopeType.PROJECT, 34L, "USD")))
                .get(0).budget()).isEqualTo(org);
        assertThat(service.lockBudgets(9L, List.of(21L, 20L, 21L))).containsExactly(exact, org);
        assertThat(service.lockCommitments(9L, List.of(30L))).extracting(BudgetCommitment::id)
                .containsExactly(30L);

        InOrder order = inOrder(budgets);
        order.verify(budgets).selectByIdForUpdate(9L, 20L);
        order.verify(budgets).selectByIdForUpdate(9L, 21L);
    }

    private static AllocationDecision decision(long id, AllocationSubjectType subjectType,
            long subjectId) {
        return new AllocationDecision(id, 9L, subjectType,
                subjectType == AllocationSubjectType.CHARGE_FACT ? subjectId : null,
                subjectType == AllocationSubjectType.EXPENSE_CLAIM ? subjectId : null,
                AllocationDecisionSource.MANUAL, null, AllocationDecisionStatus.CONFIRMED, 31L,
                EFFECTIVE_AT);
    }

    private static AllocationLine line(long id, int index, String amount) {
        return new AllocationLine(id, 9L, 71L, index, money(amount), "USD", 33L, null, null,
                EFFECTIVE_AT);
    }

    private static Budget budget(long id, long periodId, String scope, long scopeId,
            String currency) {
        return new Budget(id, 9L, periodId, com.aicostops.iam.domain.ScopeType.valueOf(scope),
                scopeId, currency, money("100.00000000"), money("0.00000000"),
                money("0.00000000"), BudgetStatus.ACTIVE, 0L, EFFECTIVE_AT, EFFECTIVE_AT);
    }

    private static BudgetCommitment commitment(long id, long budgetId) {
        return new BudgetCommitment(id, 9L, budgetId, BudgetCommitmentStatus.ACTIVE,
                money("10.00000000"), money("10.00000000"), money("10.00000000"), 0L,
                EFFECTIVE_AT, EFFECTIVE_AT);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
