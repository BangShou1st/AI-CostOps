package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.ReconciliationMoney;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.domain.ReconciliationRun;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.EvidenceRow;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ReconciliationResponses {

    private ReconciliationResponses() {
    }

    public record RunResponse(
            String id,
            String billingPeriodId,
            String status,
            String algorithmVersion,
            String toleranceAmount,
            String basisHash,
            JsonNode summary,
            String createdByMemberId,
            Instant startedAt,
            Instant finishedAt,
            String errorCode,
            String errorSummary,
            Instant createdAt,
            Instant updatedAt) {

        public static RunResponse from(ReconciliationRun run, ObjectMapper mapper) {
            return new RunResponse(
                    Long.toString(run.id()),
                    Long.toString(run.billingPeriodId()),
                    run.status().name(),
                    run.algorithmVersion(),
                    ReconciliationMoney.format(run.toleranceAmount()),
                    run.basisHash(),
                    mapper.readTree(run.summaryJson()),
                    Long.toString(run.createdByMemberId()),
                    run.startedAt(), run.finishedAt(), run.errorCode(), run.errorSummary(),
                    run.createdAt(), run.updatedAt());
        }
    }

    public record CaseResponse(
            String id,
            String reconciliationRunId,
            String providerAccountId,
            String currency,
            String caseType,
            String externalAmount,
            String internalAmount,
            String differenceAmount,
            long externalRowCount,
            long internalRowCount,
            String status,
            String reasonCode,
            String resolutionNote,
            String resolvedByMemberId,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static CaseResponse from(ReconciliationCase value) {
            return new CaseResponse(
                    Long.toString(value.id()),
                    Long.toString(value.reconciliationRunId()),
                    Long.toString(value.providerAccountId()),
                    value.currency(), value.caseType().name(),
                    value.externalAmount() == null ? null : ReconciliationMoney.format(value.externalAmount()),
                    value.internalAmount() == null ? null : ReconciliationMoney.format(value.internalAmount()),
                    ReconciliationMoney.format(value.differenceAmount()),
                    value.externalRowCount(), value.internalRowCount(), value.status().name(),
                    value.reasonCode(), value.resolutionNote(),
                    value.resolvedByMemberId() == null ? null : Long.toString(value.resolvedByMemberId()),
                    value.resolvedAt(), value.createdAt(), value.updatedAt());
        }
    }

    public record EvidenceResponse(
            String id,
            String reconciliationRunId,
            String reconciliationCaseId,
            String evidenceKey,
            String providerAccountId,
            String currency,
            String matchKind,
            String differenceKind,
            String chargeFactId,
            String gatewayRequestId,
            String gatewayRouteAttemptId,
            String gatewayUsageFactId,
            String gatewaySettlementId,
            String correctionGroupId,
            String reconciliationAdjustmentId,
            String gatewayFinancialResolutionId,
            String ledgerPostingId,
            String providerRequestId,
            String externalAmount,
            String internalAmount,
            String differenceAmount,
            Instant createdAt) {

        public static EvidenceResponse from(EvidenceRow row) {
            return new EvidenceResponse(
                    Long.toString(row.id()),
                    Long.toString(row.reconciliationRunId()),
                    row.reconciliationCaseId() == null ? null
                            : Long.toString(row.reconciliationCaseId()),
                    row.evidenceKey(),
                    Long.toString(row.providerAccountId()),
                    row.currency(),
                    row.matchKind(),
                    row.differenceKind(),
                    row.chargeFactId() == null ? null : Long.toString(row.chargeFactId()),
                    row.gatewayRequestId() == null ? null : Long.toString(row.gatewayRequestId()),
                    row.gatewayRouteAttemptId() == null ? null
                            : Long.toString(row.gatewayRouteAttemptId()),
                    row.gatewayUsageFactId() == null ? null
                            : Long.toString(row.gatewayUsageFactId()),
                    row.gatewaySettlementId() == null ? null
                            : Long.toString(row.gatewaySettlementId()),
                    row.correctionGroupId() == null ? null
                            : Long.toString(row.correctionGroupId()),
                    row.reconciliationAdjustmentId() == null ? null
                            : Long.toString(row.reconciliationAdjustmentId()),
                    row.gatewayFinancialResolutionId() == null ? null
                            : Long.toString(row.gatewayFinancialResolutionId()),
                    row.ledgerPostingId() == null ? null : Long.toString(row.ledgerPostingId()),
                    row.providerRequestId(),
                    row.externalAmount() == null ? null : ReconciliationMoney.format(row.externalAmount()),
                    row.internalAmount() == null ? null : ReconciliationMoney.format(row.internalAmount()),
                    row.differenceAmount() == null ? null : ReconciliationMoney.format(row.differenceAmount()),
                    row.createdAt());
        }
    }

    public record ChargeDispositionResponse(
            String id,
            String caseId,
            String chargeFactId,
            String disposition,
            String decisionSource) {

        public static ChargeDispositionResponse from(long dispositionId, long caseId,
                long chargeFactId, String disposition) {
            return new ChargeDispositionResponse(
                    Long.toString(dispositionId),
                    Long.toString(caseId),
                    Long.toString(chargeFactId),
                    disposition,
                    "MANUAL");
        }
    }

    public record AdjustmentResponse(
            String id,
            String caseId,
            String runId,
            String adjustmentScope,
            String amount,
            String currency,
            String adjustmentPeriodId) {

        public static AdjustmentResponse from(long caseId,
                com.aicostops.reconciliation.application.ReconciliationAdjustmentService
                        .CaseFullAdjustmentResult result) {
            return new AdjustmentResponse(
                    Long.toString(result.adjustmentId()),
                    result.caseId() == null ? Long.toString(caseId)
                            : Long.toString(result.caseId()),
                    Long.toString(result.runId()),
                    "CASE_FULL",
                    ReconciliationMoney.format(result.amount()),
                    result.currency(),
                    null);
        }
    }

    public record CorrectionLinkResponse(String caseId, String correctionGroupId) {
    }

    public record GatewayResolutionResponse(
            String id,
            String runId,
            String caseId,
            String requestId,
            String resolutionType,
            String reservationOutcome,
            String adjustmentId) {

        public static GatewayResolutionResponse from(long resolutionId, long runId, Long caseId,
                long requestId, String resolutionType, String reservationOutcome,
                Long adjustmentId) {
            return new GatewayResolutionResponse(
                    Long.toString(resolutionId),
                    Long.toString(runId),
                    caseId == null ? null : Long.toString(caseId),
                    Long.toString(requestId),
                    resolutionType,
                    reservationOutcome,
                    adjustmentId == null ? null : Long.toString(adjustmentId));
        }
    }
}
