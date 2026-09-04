package com.aicostops.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;

/** Narrow first-class Ledger seam for Backend Gateway Settlement. */
public interface GatewaySettlementLedgerPort {

    long post(PostCommand command);

    record PostCommand(
            long organizationId,
            long settlementId,
            long billingPeriodId,
            BigDecimal amount,
            String currency,
            String financialScopeType,
            long financialScopeId,
            Long budgetId,
            Instant now) {
    }
}
