package com.aicostops.gateway.provider;

import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;

/** Bounded transport evidence used by the failover coordinator. */
public class ProviderExecutionException extends GatewayErrorException {

    private final ProviderSafetyOutcome safetyOutcome;
    private final ProviderSafetyReason safetyReason;
    private final ProviderHealthSignal healthSignal;
    private final Integer httpStatus;
    private final String providerRequestId;
    private final boolean responseStarted;

    public ProviderExecutionException(
            ProviderSafetyOutcome safetyOutcome,
            ProviderSafetyReason safetyReason,
            ProviderHealthSignal healthSignal,
            Integer httpStatus,
            String providerRequestId,
            boolean responseStarted,
            Throwable cause) {
        super(gatewayCode(safetyReason), safeMessage(safetyReason, httpStatus));
        this.safetyOutcome = safetyOutcome;
        this.safetyReason = safetyReason;
        this.healthSignal = healthSignal;
        this.httpStatus = httpStatus;
        this.providerRequestId = bounded(providerRequestId, 128);
        this.responseStarted = responseStarted;
        if (cause != null) initCause(cause);
    }

    public ProviderSafetyOutcome safetyOutcome() { return safetyOutcome; }
    public ProviderSafetyReason safetyReason() { return safetyReason; }
    public ProviderHealthSignal healthSignal() { return healthSignal; }
    public Integer httpStatus() { return httpStatus; }
    public String providerRequestId() { return providerRequestId; }
    public boolean responseStarted() { return responseStarted; }

    private static GatewayErrorCode gatewayCode(ProviderSafetyReason reason) {
        return reason == ProviderSafetyReason.HEADER_TIMEOUT_WRITE_POSSIBLE
                || reason == ProviderSafetyReason.READ_TIMEOUT
                || reason == ProviderSafetyReason.STREAM_TIMEOUT
                ? GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT : GatewayErrorCode.GATEWAY_UPSTREAM_FAILED;
    }

    private static String safeMessage(ProviderSafetyReason reason, Integer status) {
        return status == null ? "Provider request could not be completed (" + reason + ")"
                : "Provider request failed with HTTP " + status;
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
