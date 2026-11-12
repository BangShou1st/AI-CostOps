package com.aicostops.intelligence.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.intelligence.domain.CostAnomalyEngine.DailyBucket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * P2: driver deltas share the anomaly baseline basis — only observed days are evidence, the
 * evaluated bucket must itself be observed, and even-sized samples use the true median (the two
 * middle values), not the upper middle value.
 */
class CostIntelligenceLatestDeltaTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 29);

    @Test
    void evenSizedObservedHistoryUsesTheMedianInsteadOfTheUpperMiddleValue() {
        var buckets = new ArrayList<DailyBucket>();
        for (var i = 0; i < 28; i++) {
            buckets.add(new DailyBucket(END.minusDays(28 - i),
                    new BigDecimal(i < 14 ? "10.00" : "20.00"), "USD"));
        }
        buckets.add(new DailyBucket(END, new BigDecimal("35.00"), "USD"));

        // median of 14 x 10.00 and 14 x 20.00 is 15.00; the old upper-middle pick was 20.00.
        assertThat(CostIntelligenceAnalysisService.latestDelta(buckets))
                .isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    void unobservedGapDaysDoNotDiluteTheDriverBaseline() {
        var buckets = new ArrayList<DailyBucket>();
        for (var i = 0; i < 28; i++) {
            var observed = i >= 14;
            var amount = observed ? (i < 21 ? "10.00" : "20.00") : "0.00";
            buckets.add(new DailyBucket(END.minusDays(28 - i), new BigDecimal(amount), "USD", observed));
        }
        buckets.add(new DailyBucket(END, new BigDecimal("25.00"), "USD"));

        // 14 observed values (7 x 10.00, 7 x 20.00) median 15.00 -> 10.00. Counting the 14 gaps
        // as zeros pushed the old upper-middle pick to 10.00 -> 15.00.
        assertThat(CostIntelligenceAnalysisService.latestDelta(buckets))
                .isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void unobservedEvaluationBucketHasNoDriverDelta() {
        var buckets = new ArrayList<DailyBucket>();
        for (var i = 0; i < 28; i++) {
            buckets.add(new DailyBucket(END.minusDays(28 - i), new BigDecimal("10.00"), "USD"));
        }
        buckets.add(new DailyBucket(END, new BigDecimal("30.00"), "USD", false));

        assertThat(CostIntelligenceAnalysisService.latestDelta(buckets)).isNull();
    }

    @Test
    void insufficientObservedHistoryHasNoDriverDelta() {
        var buckets = new ArrayList<DailyBucket>();
        for (var i = 0; i < 28; i++) {
            var observed = i < 13;
            buckets.add(new DailyBucket(END.minusDays(28 - i),
                    new BigDecimal(observed ? "10.00" : "0.00"), "USD", observed));
        }
        buckets.add(new DailyBucket(END, new BigDecimal("30.00"), "USD"));

        assertThat(CostIntelligenceAnalysisService.latestDelta(buckets)).isNull();
    }
}
