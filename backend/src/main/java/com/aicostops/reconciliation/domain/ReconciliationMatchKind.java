package com.aicostops.reconciliation.domain;

/** Bounded match kinds for M15 hybrid reconciliation evidence. */
public enum ReconciliationMatchKind {
    EXACT_PROVIDER_REQUEST,
    AGGREGATE_SCOPE,
    GATEWAY_UNRESOLVED,
    MANUAL_BINDING,
    RESOLUTION_ACTION
}
