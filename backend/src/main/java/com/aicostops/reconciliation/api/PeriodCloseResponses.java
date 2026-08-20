package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.application.PeriodCloseQueryService.CloseReadiness;
import com.aicostops.reconciliation.application.PeriodCloseReadModels.PeriodCloseView;
import com.aicostops.reconciliation.domain.PeriodCloseCheck;
import java.time.Instant;
import java.util.List;

public final class PeriodCloseResponses {

    private PeriodCloseResponses() {
    }

    public record CheckResponse(
            String blockerCode,
            String result,
            long itemCount,
            Object summary,
            Instant evaluatedAt) {

        static CheckResponse fromPreview(CloseBlockerResult value) {
            return new CheckResponse(value.code().name(), value.result().name(),
                    value.itemCount(), value.summary(), null);
        }

        static CheckResponse fromPersisted(PeriodCloseCheck value, tools.jackson.databind.ObjectMapper mapper) {
            return new CheckResponse(value.blockerCode().name(), value.result().name(),
                    value.itemCount(), mapper.readTree(value.summaryJson()), value.evaluatedAt());
        }
    }

    public record ReadinessResponse(
            String billingPeriodId,
            String periodStatus,
            String closeGeneration,
            boolean ready,
            boolean preview,
            List<CheckResponse> checks) {
        static ReadinessResponse from(CloseReadiness value) {
            return new ReadinessResponse(
                    Long.toString(value.period().id()),
                    value.period().status().name(),
                    Long.toString(value.period().closeGeneration()),
                    value.ready(), true,
                    value.checks().stream().map(CheckResponse::fromPreview).toList());
        }
    }

    public record CloseRunResponse(
            String billingPeriodId,
            String periodStatus,
            String closeGeneration,
            Instant closingStartedAt,
            Instant closedAt,
            Instant reopenedAt,
            String runId,
            String runStatus,
            int attemptNo,
            String reconciliationRunId,
            String startedByMemberId,
            Instant startedAt,
            Instant finishedAt,
            String errorCode,
            String errorSummary,
            List<CheckResponse> checks) {

        static CloseRunResponse from(PeriodCloseView value, tools.jackson.databind.ObjectMapper mapper) {
            return new CloseRunResponse(
                    Long.toString(value.period().id()),
                    value.period().status().name(),
                    Long.toString(value.period().closeGeneration()),
                    value.period().closingStartedAt(), value.period().closedAt(), value.period().reopenedAt(),
                    Long.toString(value.run().id()), value.run().status().name(), value.run().attemptNo(),
                    value.run().reconciliationRunId() == null
                            ? null : Long.toString(value.run().reconciliationRunId()),
                    Long.toString(value.run().startedByMemberId()),
                    value.run().startedAt(), value.run().finishedAt(),
                    value.run().errorCode(), value.run().errorSummary(),
                    value.checks().stream().map(check -> CheckResponse.fromPersisted(check, mapper)).toList());
        }
    }
}
