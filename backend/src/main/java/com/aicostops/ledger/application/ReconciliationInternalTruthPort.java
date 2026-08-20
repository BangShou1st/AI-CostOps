package com.aicostops.ledger.application;

import java.math.BigDecimal;
import java.util.List;

/** Immutable provider-ledger truth for M6 reconciliation. */
public interface ReconciliationInternalTruthPort {

    List<InternalAggregate> aggregateProviderLedger(
            long organizationId, long billingPeriodId);

    record InternalAggregate(
            long providerAccountId,
            String currency,
            long rowCount,
            BigDecimal amount) {
    }
}
