package com.aicostops.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Caller-transaction-friendly append-only Ledger seam for Reconciliation
 * Adjustments. The adapter never opens its own transaction: the reconciliation
 * service owns the financial transaction and the deterministic lock order.
 */
public interface ReconciliationAdjustmentLedgerPort {

    record AdjustmentLineCommand(
            int entryIndex,
            BigDecimal amount,
            String currency,
            Long projectId,
            Long costCenterId,
            Long teamId,
            Long budgetId) {
    }

    record AdjustmentPostCommand(
            long organizationId,
            long adjustmentId,
            long billingPeriodId,
            List<AdjustmentLineCommand> lines,
            long postedByMemberId,
            Instant postedAt) {
    }

    record AdjustmentPostResult(long postingId, java.util.List<Long> entryIds) {
    }

    /** Inserts the posting and its entries; returns the posting and entry ids. */
    AdjustmentPostResult postAdjustment(AdjustmentPostCommand command);
}
