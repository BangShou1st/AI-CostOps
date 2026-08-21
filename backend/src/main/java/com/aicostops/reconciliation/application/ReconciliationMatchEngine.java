package com.aicostops.reconciliation.application;

import com.aicostops.cost.application.ReconciliationExternalTruthPort.ExternalAggregate;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort.InternalAggregate;
import com.aicostops.reconciliation.application.ReconciliationReadModels.MatchRow;
import com.aicostops.reconciliation.application.ReconciliationReadModels.MatchSummary;
import com.aicostops.reconciliation.domain.ReconciliationCaseType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public final class ReconciliationMatchEngine {

    private static final BigDecimal ZERO = new BigDecimal("0.00000000");

    public MatchSummary match(
            List<ExternalAggregate> external,
            List<InternalAggregate> internal,
            BigDecimal tolerance) {
        var exactTolerance = ReconciliationMoney.requireScale8Exact(tolerance);
        if (exactTolerance.signum() < 0) {
            throw new IllegalArgumentException("tolerance must be non-negative");
        }

        Map<Key, Aggregate> externalByKey = new TreeMap<>();
        for (var row : external) {
            requireAggregate(row.providerAccountId(), row.currency(), row.rowCount(), row.amount());
            externalByKey.merge(new Key(row.providerAccountId(), row.currency()),
                    new Aggregate(row.rowCount(), ReconciliationMoney.requireScale8Exact(row.amount())),
                    Aggregate::plus);
        }

        Map<Key, Aggregate> internalByKey = new TreeMap<>();
        for (var row : internal) {
            requireAggregate(row.providerAccountId(), row.currency(), row.rowCount(), row.amount());
            internalByKey.merge(new Key(row.providerAccountId(), row.currency()),
                    new Aggregate(row.rowCount(), ReconciliationMoney.requireScale8Exact(row.amount())),
                    Aggregate::plus);
        }

        var keys = new TreeMap<Key, Boolean>();
        externalByKey.keySet().forEach(key -> keys.put(key, Boolean.TRUE));
        internalByKey.keySet().forEach(key -> keys.put(key, Boolean.TRUE));

        var rows = new ArrayList<MatchRow>();
        long matched = 0;
        long discrepancies = 0;
        for (var key : keys.keySet()) {
            var ext = externalByKey.get(key);
            var in = internalByKey.get(key);
            var externalPresent = ext != null && ext.rowCount() > 0;
            var internalPresent = in != null && in.rowCount() > 0;
            var externalAmount = externalPresent ? ext.amount() : null;
            var internalAmount = internalPresent ? in.amount() : null;
            var difference = ReconciliationMoney.requireScale8Exact(
                    (internalAmount == null ? ZERO : internalAmount)
                            .subtract(externalAmount == null ? ZERO : externalAmount));

            ReconciliationCaseType caseType = null;
            if (externalPresent && !internalPresent) {
                caseType = ReconciliationCaseType.MISSING_INTERNAL;
            } else if (!externalPresent && internalPresent) {
                caseType = ReconciliationCaseType.MISSING_EXTERNAL;
            } else if (externalPresent && internalPresent
                    && difference.abs().compareTo(exactTolerance) > 0) {
                caseType = ReconciliationCaseType.AMOUNT_MISMATCH;
            }

            if (caseType == null) {
                matched++;
            } else {
                discrepancies++;
            }
            rows.add(new MatchRow(
                    key.providerAccountId(), key.currency(),
                    externalPresent, ext == null ? 0 : ext.rowCount(), externalAmount,
                    internalPresent, in == null ? 0 : in.rowCount(), internalAmount,
                    difference, caseType));
        }
        return new MatchSummary(rows, matched, discrepancies);
    }

    private static void requireAggregate(long providerAccountId, String currency,
            long rowCount, BigDecimal amount) {
        if (providerAccountId <= 0) {
            throw new IllegalArgumentException("providerAccountId must be positive");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be ISO-style uppercase CHAR(3)");
        }
        if (rowCount <= 0) {
            throw new IllegalArgumentException("aggregate rowCount must be positive");
        }
        ReconciliationMoney.requireScale8Exact(amount);
    }

    private record Key(long providerAccountId, String currency) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            var byProvider = Long.compare(providerAccountId, other.providerAccountId);
            return byProvider != 0 ? byProvider : currency.compareTo(other.currency);
        }
    }

    private record Aggregate(long rowCount, BigDecimal amount) {
        Aggregate plus(Aggregate other) {
            return new Aggregate(rowCount + other.rowCount,
                    ReconciliationMoney.requireScale8Exact(amount.add(other.amount)));
        }
    }
}
