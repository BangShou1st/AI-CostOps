package com.aicostops.reconciliation.api;

public final class ReconciliationRequests {

    private ReconciliationRequests() {
    }

    public record RunRequest(String billingPeriodId) {
    }

    public record ResolveCaseRequest(String reasonCode, String resolutionNote) {
    }

    public record ChargeDispositionRequest(
            String chargeFactId,
            String disposition,
            String reasonCode,
            String reasonNote) {
    }

    public record CaseAdjustmentRequest(
            String amount,
            String adjustmentPeriodId,
            java.util.List<AdjustmentLineRequest> lines,
            String reasonCode,
            String reasonNote) {

        public record AdjustmentLineRequest(
                String lineIndex,
                String scopeType,
                String scopeId,
                String amount) {
        }
    }

    public record GatewayResolutionRequest(
            String caseId,
            String requestId,
            String resolutionType,
            String adjustmentAmount,
            String correctionPeriodId,
            String commitmentId,
            String reasonCode,
            String reasonNote) {
    }

    public record LinkCorrectionRequest(String correctionGroupId) {
    }
}
