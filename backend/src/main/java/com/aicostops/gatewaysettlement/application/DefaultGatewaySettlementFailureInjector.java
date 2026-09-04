package com.aicostops.gatewaysettlement.application;

import org.springframework.stereotype.Component;

/** Production fault seam is inert; tests may replace it to prove rollback. */
@Component
public final class DefaultGatewaySettlementFailureInjector
        implements GatewaySettlementFailureInjector {

    @Override
    public void after(String financialStage) {
        // Intentionally empty in production.
    }
}
