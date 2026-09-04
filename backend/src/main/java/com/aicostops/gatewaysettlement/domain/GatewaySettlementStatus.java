package com.aicostops.gatewaysettlement.domain;

/** Durable Gateway settlement state. PROCESSING/CLAIMED states are forbidden. */
public enum GatewaySettlementStatus {
    PENDING,
    RETRYABLE_FAILED,
    RECONCILIATION_REQUIRED,
    SETTLED;

    public boolean isAutomaticCandidate() {
        return this == PENDING || this == RETRYABLE_FAILED;
    }

    public boolean isTerminal() {
        return this == SETTLED || this == RECONCILIATION_REQUIRED;
    }
}
