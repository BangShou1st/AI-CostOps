package com.aicostops.cost.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One row of {@code external_document}: a provider-reported billing document. */
public record ExternalDocument(
        long orgId,
        long rawRecordId,
        int factIndex,
        DocumentType documentType,
        Instant periodStart,
        Instant periodEnd,
        String currency,
        BigDecimal reportedTotalAmount,
        BigDecimal reportedPayableAmount,
        BigDecimal reportedPaidAmount,
        BigDecimal reportedOutstandingAmount,
        Map<String, Object> metadata) {

    public ExternalDocument {
        metadata = metadata == null ? null : Map.copyOf(metadata);
    }
}
