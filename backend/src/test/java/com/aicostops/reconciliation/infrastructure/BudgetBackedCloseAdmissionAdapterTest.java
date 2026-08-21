package com.aicostops.reconciliation.infrastructure;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class BudgetBackedCloseAdmissionAdapterTest {

    private final BillingPeriodFinancialWriteFence fence = mock(BillingPeriodFinancialWriteFence.class);
    private final ImportChargePeriodMapper importChargePeriods = mock(ImportChargePeriodMapper.class);
    private final BudgetBackedCloseAdmissionAdapter adapter =
            new BudgetBackedCloseAdmissionAdapter(fence, importChargePeriods);

    @Test
    void locksOrganizationThenDistinctPeriodsInAscendingOrder() {
        var earlier = Instant.parse("2026-08-01T00:00:00Z");
        var later = Instant.parse("2026-09-01T00:00:00Z");
        when(importChargePeriods.findContributingPeriodStarts(7L, 9L))
                .thenReturn(List.of(later, earlier, later));

        adapter.lockAndRequireOpenPeriodsForAttempt(7L, 9L);

        InOrder order = inOrder(fence);
        order.verify(fence).lockOrganizationAndRequireNoClosingPeriod(7L);
        order.verify(fence).lockOpenAt(7L, earlier);
        order.verify(fence).lockOpenAt(7L, later);
        verify(importChargePeriods).findContributingPeriodStarts(7L, 9L);
    }
}
