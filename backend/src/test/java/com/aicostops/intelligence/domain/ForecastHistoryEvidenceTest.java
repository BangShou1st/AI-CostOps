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

    /**
     * U1 eligibility: reversal-dominated (non-positive net) history cannot be extrapolated into
     * future usage. Negative nets never produce a forecast; mixed signs qualify only when the
     * window nets positive; observed all-zero history legitimately projects zero.
     */
    @Test void nonPositiveNetsAreUnavailableWhileAllZeroHistoryProjectsZero() {
        for (var days : new int[] {3, 7, 13, 14, 21}) {
            assertThrows(ForecastEngine.ForecastUnavailableException.class,
                    () -> ForecastEngine.forecast(padded(days, "-1"), 20, END),
                    () -> days + " days of -1.00 must not become a negative forecast");
        }
        // Mixed signs that net positive stay eligible (one -1 among +2 days):
        // window net 5.00 over 4 days = 1.25/day; 1.25 * 5 remaining days = 6.25.
        var mixed = padded(4, "2.00");
        mixed.set(28, new CostAnomalyEngine.DailyBucket(END, new BigDecimal("-1"), "CNY", true));
        var mixedForecast = ForecastEngine.forecast(mixed, 5, END);
        assertEquals(0, mixedForecast.projectedAmount().compareTo(new BigDecimal("6.25000000")));
        // Mixed signs that net to zero or below are unavailable.
        var netZero = padded(4, "0.50");
        netZero.set(28, new CostAnomalyEngine.DailyBucket(END, new BigDecimal("-1.50"), "CNY", true));
        assertThrows(ForecastEngine.ForecastUnavailableException.class,
                () -> ForecastEngine.forecast(netZero, 20, END));
        // Observed all-zero history is a legitimate zero forecast, not unavailable.
        var zeros = ForecastEngine.forecast(padded(3, "0"), 20, END);
        assertEquals(0, zeros.projectedAmount().compareTo(BigDecimal.ZERO));
    }

    @Test void dampedHoltPathAlsoRefusesNegativeNetHistory() {
        var negative = padded(21, "-1");
        assertThrows(ForecastEngine.ForecastUnavailableException.class,
                () -> ForecastEngine.forecast(negative, 20, END));
    }
}
