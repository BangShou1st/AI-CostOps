package com.aicostops.reconciliation.application.blockers;

import com.aicostops.expense.application.ExpenseCloseBlockerPort;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class UnpostedApprovedExpensesBlockerProvider implements CloseBlockerProvider {
    private static final int SAMPLE_LIMIT = 20;
    private final ExpenseCloseBlockerPort expenses;

    public UnpostedApprovedExpensesBlockerProvider(ExpenseCloseBlockerPort expenses) {
        this.expenses = expenses;
    }

    @Override public CloseBlockerCode code() { return CloseBlockerCode.UNPOSTED_APPROVED_EXPENSES; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var count = expenses.countApprovedUnposted(
                context.organizationId(), context.periodStart(), context.periodEnd());
        var summary = Map.<String, Object>of(
                "sampleExpenseClaimIds", expenses.sampleApprovedUnpostedIds(
                        context.organizationId(), context.periodStart(), context.periodEnd(), SAMPLE_LIMIT),
                "effectiveTimeRule", "expense_date@00:00:00Z");
        return count == 0 ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), count, summary);
    }
}
