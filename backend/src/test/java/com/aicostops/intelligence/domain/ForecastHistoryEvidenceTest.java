package com.aicostops.intelligence.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ForecastHistoryEvidenceTest {
    private static final LocalDate END = LocalDate.of(2026, 10, 11);

    private ArrayList<CostAnomalyEngine.DailyBucket> padded(int observedDays, String amount) {
        var buckets = new ArrayList<CostAnomalyEngine.DailyBucket>();
        for (var i = 28; i >= 0; i--) {
            var observed = i < observedDays;
            buckets.add(new CostAnomalyEngine.DailyBucket(END.minusDays(i),
                    observed ? new BigDecimal(amount) : BigDecimal.ZERO, "CNY", observed));
        }
        return buckets;
    }

    @Test void oneRealPostingCannotBecomeTwentyNineHighConfidenceDays() {
        assertThrows(IllegalArgumentException.class,
                () -> ForecastEngine.forecast(padded(1, "7.25"), 19, END));
        assertThrows(IllegalArgumentException.class,
                () -> ForecastEngine.forecast(padded(2, "1"), 19, END));
        assertThrows(IllegalArgumentException.class,
                () -> ForecastEngine.forecast(padded(0, "0"), 19, END));
    }

    @ParameterizedTest
    @CsvSource({"3,RECENT_RUN_RATE,LOW", "13,RECENT_RUN_RATE,LOW", "14,DAMPED_HOLT,MEDIUM",
            "20,DAMPED_HOLT,MEDIUM", "21,DAMPED_HOLT,HIGH", "29,DAMPED_HOLT,HIGH"})
    void methodsAndConfidenceUseOnlyConsecutiveObservedDays(int days, String method, String confidence) {
        var forecast = ForecastEngine.forecast(padded(days, "2.00"), 5, END);
        assertEquals(days, forecast.historyBucketCount());
        assertEquals(method, forecast.method());
        assertEquals(confidence, forecast.confidence());
        assertEquals(0, forecast.projectedAmount().compareTo(new BigDecimal("10.00")));
    }

    @Test void observedZeroAmountsCountButUnknownZerosBreakContinuity() {
        var zeros = ForecastEngine.forecast(padded(3, "0"), 5, END);
        assertEquals(3, zeros.historyBucketCount());
        assertEquals(BigDecimal.ZERO, zeros.projectedAmount().stripTrailingZeros());
        var interrupted = padded(29, "2.00");
        interrupted.set(26, new CostAnomalyEngine.DailyBucket(END.minusDays(2), BigDecimal.ZERO, "CNY", false));
        assertThrows(IllegalArgumentException.class, () -> ForecastEngine.forecast(interrupted, 5, END));
        interrupted.set(28, new CostAnomalyEngine.DailyBucket(END, BigDecimal.ZERO, "CNY", false));
        assertThrows(IllegalArgumentException.class, () -> ForecastEngine.forecast(interrupted, 5, END));
    }

    @Test void sparseDatesAndStaleHistoryCannotBeCompressedIntoDailyEvidence() {
        var gaps = padded(29, "2.00");
        gaps.remove(12);
        assertThrows(IllegalArgumentException.class, () -> ForecastEngine.forecast(gaps, 5, END));
        assertThrows(IllegalArgumentException.class, () -> ForecastEngine.forecast(padded(29,"2.00"), 5, END.plusDays(1)));
    }
}
