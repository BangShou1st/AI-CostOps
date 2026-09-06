package com.aicostops.reconciliation.application;

import org.springframework.stereotype.Component;

/** Production fault seam is inert; tests may replace it to prove rollback. */
@Component
public final class DefaultGatewayResolutionFailureInjector
        implements GatewayResolutionFailureInjector {

    @Override
    public void after(String financialStage) {
        // Intentionally empty in production. financialStage is a deliberate
        // test seam: rollback tests stub a specific financial checkpoint, so
        // the stage must always be named.
        java.util.Objects.requireNonNull(financialStage, "financialStage");
    }
}
