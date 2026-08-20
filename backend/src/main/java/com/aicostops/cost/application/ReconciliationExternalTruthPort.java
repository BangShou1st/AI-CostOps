package com.aicostops.cost.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Canonical confirmed-import provider truth for M6 reconciliation. */
public interface ReconciliationExternalTruthPort {

    List<ExternalAggregate> aggregateConfirmedCharges(
            long organizationId, Instant periodStart, Instant periodEnd);

    record ExternalAggregate(
            long providerAccountId,
            String currency,
            long rowCount,
            BigDecimal amount) {
    }
}
