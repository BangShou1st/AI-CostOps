package com.aicostops.reconciliation.application;

/** Test seam for proving the CASE_FULL adjustment transaction rolls back. */
@FunctionalInterface
public interface ReconciliationAdjustmentFailureInjector {

    void after(String financialStage);

    static ReconciliationAdjustmentFailureInjector noop() {
        return stage -> {
        };
    }
}
