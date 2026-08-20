package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.application.PeriodCloseQueryService.CloseReadiness;
import java.util.List;
import java.util.Map;

public final class PeriodCloseResponses {

    private PeriodCloseResponses() {
    }

    public record CheckResponse(
            String blockerCode,
            String result,
            long itemCount,
            Map<String, Object> summary) {
        static CheckResponse from(CloseBlockerResult value) {
            return new CheckResponse(value.code().name(), value.result().name(),
                    value.itemCount(), value.summary());
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
                    value.checks().stream().map(CheckResponse::from).toList());
        }
    }
}
