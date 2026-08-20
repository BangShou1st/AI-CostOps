package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.ReconciliationMoney;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.domain.ReconciliationRun;
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
}
