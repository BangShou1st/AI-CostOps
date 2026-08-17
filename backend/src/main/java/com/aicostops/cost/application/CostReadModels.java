package com.aicostops.cost.application;

import com.aicostops.cost.domain.ChargeCategory;
import com.aicostops.cost.domain.ReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read models for the ChargeFact cost read API. Money stays a scale-8
 * {@link BigDecimal} until the HTTP layer stringifies it with
 * {@code toPlainString()}.
 */
public final class CostReadModels {

    private CostReadModels() {
    }

    /** One row of the paged charge list. */
    public record ChargeCostRow(
            long id,
            long organizationId,
            String providerCode,
            ChargeCategory chargeCategory,
            BigDecimal amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus,
            Long currentAllocationDecisionId) {
    }

    /** Charge detail: list fields plus allocation/duplicate workflow state. */
    public record ChargeCostDetailRow(
            long id,
            long organizationId,
            String providerCode,
            ChargeCategory chargeCategory,
            BigDecimal amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus,
            Long currentAllocationDecisionId,
            Long duplicateOfChargeId,
            boolean confirmedImport) {
    }
}
