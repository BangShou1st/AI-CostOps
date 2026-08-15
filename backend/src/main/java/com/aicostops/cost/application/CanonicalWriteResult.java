package com.aicostops.cost.application;

/** Row counts written by one {@link CanonicalCostWritePort#write(CanonicalizationInput)} call. */
public record CanonicalWriteResult(
        int documents,
        int consumptions,
        int pricings,
        int charges,
        int hints) {
}
