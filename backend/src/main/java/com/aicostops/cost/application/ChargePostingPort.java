package com.aicostops.cost.application;

import com.aicostops.cost.domain.ReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Posting-specific source contract owned by the cost module. */
public interface ChargePostingPort {

    ChargePostingSource load(long organizationId, long chargeFactId);

    ChargePostingSource lockAndRequirePostable(
            long organizationId, long chargeFactId, long expectedDecisionId);

    record ChargePostingSource(
            long id,
            BigDecimal amount,
            String currency,
            Instant periodStart,
            Long currentAllocationDecisionId,
            ReviewStatus reviewStatus,
            boolean confirmedImport) {
    }
}
