package com.aicostops.reconciliation.api;

public final class ReconciliationRequests {

    private ReconciliationRequests() {
    }

    public record RunRequest(String billingPeriodId) {
    }

    public record ResolveCaseRequest(String reasonCode, String resolutionNote) {
    }
}
