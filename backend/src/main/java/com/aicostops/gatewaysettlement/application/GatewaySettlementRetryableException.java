package com.aicostops.gatewaysettlement.application;

/** A bounded, non-financial retry outcome for temporary settlement contention. */
public final class GatewaySettlementRetryableException extends RuntimeException {

    private final String errorCode;

    public GatewaySettlementRetryableException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
