package com.aicostops.reconciliation.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.reconciliation.domain.PeriodCloseCheck;
import com.aicostops.reconciliation.domain.PeriodCloseRun;
import java.util.List;

public final class PeriodCloseReadModels {

    private PeriodCloseReadModels() {
    }

    public record PeriodCloseView(
            BillingPeriod period,
            PeriodCloseRun run,
            List<PeriodCloseCheck> checks) {
        public PeriodCloseView {
            checks = List.copyOf(checks);
        }
    }
}
