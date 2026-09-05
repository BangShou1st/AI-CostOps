package com.aicostops.gateway.resilience;

public record CircuitDecision(CircuitState state, boolean probeAllowed) {

    public static CircuitDecision closed() {
        return new CircuitDecision(CircuitState.CLOSED, true);
    }

    public static CircuitDecision open() {
        return new CircuitDecision(CircuitState.OPEN, false);
    }

    public static CircuitDecision probe() {
        return new CircuitDecision(CircuitState.HALF_OPEN, true);
    }
}
