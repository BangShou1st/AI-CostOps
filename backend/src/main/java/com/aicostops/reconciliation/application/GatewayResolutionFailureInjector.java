package com.aicostops.reconciliation.application;

/** Test seam for proving the gateway financial resolution transaction rolls back. */
@FunctionalInterface
public interface GatewayResolutionFailureInjector {

    void after(String financialStage);

    static GatewayResolutionFailureInjector noop() {
        return stage -> {
        };
    }
}
