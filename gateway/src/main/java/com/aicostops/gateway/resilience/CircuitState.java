package com.aicostops.gateway.resilience;

public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
