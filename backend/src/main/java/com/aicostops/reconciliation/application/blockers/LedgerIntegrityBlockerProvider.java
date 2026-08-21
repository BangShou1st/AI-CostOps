package com.aicostops.reconciliation.application.blockers;

import com.aicostops.budget.application.BudgetIntegrityPort;
import com.aicostops.ledger.application.LedgerIntegrityPort;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class LedgerIntegrityBlockerProvider implements CloseBlockerProvider {
    private static final int SAMPLE_LIMIT = 20;

    private final LedgerIntegrityPort ledger;
    private final BudgetIntegrityPort budgets;

    public LedgerIntegrityBlockerProvider(LedgerIntegrityPort ledger, BudgetIntegrityPort budgets) {
        this.ledger = ledger;
        this.budgets = budgets;
    }

    @Override public CloseBlockerCode code() { return CloseBlockerCode.LEDGER_INTEGRITY; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var ledgerSnapshot = ledger.inspect(context.organizationId(), context.billingPeriodId());
        var budgetSnapshot = budgets.inspect(context.organizationId(), context.billingPeriodId());
        var total = ledgerSnapshot.total() + budgetSnapshot.total();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("postingsWithoutEntries", ledgerSnapshot.postingsWithoutEntries());
        summary.put("normalEntryMismatches", ledgerSnapshot.normalEntryMismatches());
        summary.put("normalPostingCardinalityMismatches",
                ledgerSnapshot.normalPostingCardinalityMismatches());
        summary.put("correctionMismatches", ledgerSnapshot.correctionMismatches());
        summary.put("doubleReversalTargets", ledgerSnapshot.doubleReversalTargets());
        summary.put("budgetActualAmountDrift", budgetSnapshot.actualAmountDrift());
        summary.put("budgetCommittedAmountDrift", budgetSnapshot.committedAmountDrift());
        summary.put("invalidCommitmentState", budgetSnapshot.invalidCommitmentState());
        summary.put("sampleProblemPostingIds",
                ledger.sampleProblemPostingIds(
                        context.organizationId(), context.billingPeriodId(), SAMPLE_LIMIT));
        summary.put("sampleProblemBudgetIds",
                budgets.sampleProblemBudgetIds(
                        context.organizationId(), context.billingPeriodId(), SAMPLE_LIMIT));
        return total == 0 ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), total, summary);
    }
}
