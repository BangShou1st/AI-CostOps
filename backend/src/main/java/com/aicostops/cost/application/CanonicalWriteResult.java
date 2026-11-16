package com.aicostops.cost.application;

/** Row counts written by one {@link CanonicalCostWritePort#write(CanonicalizationInput)} call. */
public record CanonicalWriteResult(
        int documents,
        int consumptions,
        int pricings,
        int charges,
        int hints) {

    public static CanonicalWriteResult empty() {
        return new CanonicalWriteResult(0, 0, 0, 0, 0);
    }

    public CanonicalWriteResult plus(CanonicalWriteResult other) {
        return new CanonicalWriteResult(
                documents + other.documents,
                consumptions + other.consumptions,
                pricings + other.pricings,
                charges + other.charges,
                hints + other.hints);
    }
}
