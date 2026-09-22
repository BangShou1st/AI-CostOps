package com.aicostops.gateway.provider;

/**
 * Configuration failure after the dispatch fence but before Provider I/O.
 * It is positively safe: no Provider request has been written.
 */
public final class ProviderContextResolutionException extends ProviderExecutionException {

    public ProviderContextResolutionException(String reason) {
        super(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE,
                ProviderHealthSignal.ROUTE_CONFIGURATION_FAILURE,
                null, null, false, new IllegalStateException(reason));
    }
}
