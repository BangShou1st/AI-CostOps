package com.aicostops.reconciliation.api;

public final class PeriodCloseRequests {

    private PeriodCloseRequests() {
    }

    public record ReopenPeriodRequest(String reasonCode, String reasonNote) {
    }
}
