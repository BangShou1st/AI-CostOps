package com.aicostops.gateway.provider;

public enum ProviderHealthSignal {
    NONE,
    QUALIFYING_FAILURE,
    ROUTE_CONFIGURATION_FAILURE,
    SUCCESS,
    CLIENT_CANCELLATION
}
