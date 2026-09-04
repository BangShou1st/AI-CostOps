package com.aicostops.gatewaysettlement.application;

/** Semantic conflict that must stop automatic settlement and be persisted as a bounded code. */
public final class GatewaySettlementReconciliationException extends RuntimeException {

    private final String errorCode;

    public GatewaySettlementReconciliationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
