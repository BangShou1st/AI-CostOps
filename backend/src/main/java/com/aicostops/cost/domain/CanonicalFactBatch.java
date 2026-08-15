package com.aicostops.cost.domain;

import java.util.List;

/**
 * Canonical facts produced for exactly one raw record. Every list is non-null;
 * per-table {@code fact_index} values run from 0 contiguously within the batch.
 */
public record CanonicalFactBatch(
        List<ExternalDocument> documents,
        List<ConsumptionFact> consumptions,
        List<PricingFact> pricings,
        List<ChargeFact> charges,
        List<AttributionHint> hints) {

    public CanonicalFactBatch {
        documents = List.copyOf(documents);
        consumptions = List.copyOf(consumptions);
        pricings = List.copyOf(pricings);
        charges = List.copyOf(charges);
        hints = List.copyOf(hints);
    }
}
