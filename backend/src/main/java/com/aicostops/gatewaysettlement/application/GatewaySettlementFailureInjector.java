package com.aicostops.gatewaysettlement.application;

/** Test seam for proving the Settlement transaction rolls back at named stages. */
@FunctionalInterface
public interface GatewaySettlementFailureInjector {

    void after(String financialStage);

    static GatewaySettlementFailureInjector noop() {
        return stage -> {
        };
    }
}
