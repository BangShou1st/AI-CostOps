package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.domain.PeriodCloseCheckResult;
import java.util.Map;

public record CloseBlockerResult(
        CloseBlockerCode code,
        PeriodCloseCheckResult result,
        long itemCount,
        Map<String, Object> summary) {

    public CloseBlockerResult {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must be non-negative");
        }
        summary = Map.copyOf(summary);
    }

    public boolean passed() {
        return result == PeriodCloseCheckResult.PASS;
    }

    public static CloseBlockerResult pass(CloseBlockerCode code, Map<String, Object> summary) {
        return new CloseBlockerResult(code, PeriodCloseCheckResult.PASS, 0, summary);
    }

    public static CloseBlockerResult fail(
            CloseBlockerCode code, long itemCount, Map<String, Object> summary) {
        return new CloseBlockerResult(code, PeriodCloseCheckResult.FAIL, itemCount, summary);
    }

    public static CloseBlockerResult error(CloseBlockerCode code, String errorCode) {
        return new CloseBlockerResult(code, PeriodCloseCheckResult.ERROR, 0,
                Map.of("errorCode", errorCode));
    }
}
