package com.aicostops.reconciliation.domain;

/**
 * Bounded difference vocabulary for M15 evidence. Classification is
 * evidence-gated: a code may only be assigned when persisted facts prove it,
 * otherwise UNCLASSIFIED is mandatory.
 */
public enum ReconciliationDifferenceKind {
    PRICING_DRIFT,
    DISCOUNT,
    ROUNDING,
    PROVIDER_CORRECTION,
    LATE_CHARGE,
    BILLING_PERIOD_MISMATCH,
    MISSING_GATEWAY_USAGE,
    UNKNOWN_PROVIDER_CHARGE,
    DUPLICATE_EXTERNAL_CHARGE,
    UNCLASSIFIED
}
