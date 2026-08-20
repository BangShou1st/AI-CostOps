package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.domain.ReconciliationCaseType;
import java.math.BigDecimal;
import java.util.List;

public final class ReconciliationReadModels {

    private ReconciliationReadModels() {
    }

    public record MatchRow(
            long providerAccountId,
            String currency,
            boolean externalPresent,
            long externalRowCount,
            BigDecimal externalAmount,
            boolean internalPresent,
            long internalRowCount,
            BigDecimal internalAmount,
            BigDecimal difference,
            ReconciliationCaseType caseType) {
    }

    public record MatchSummary(
            List<MatchRow> rows,
            long matchedCount,
            long discrepancyCount) {
        public MatchSummary {
            rows = List.copyOf(rows);
        }
    }
}
