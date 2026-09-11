package com.aicostops.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostIntelligenceEnginesTest {

    private static List<CostAnomalyEngine.DailyBucket> constantSeries(
            int days, String amount, LocalDate end) {
        var buckets = new ArrayList<CostAnomalyEngine.DailyBucket>();
        for (var i = days - 1; i >= 0; i--) {
            buckets.add(new CostAnomalyEngine.DailyBucket(
                    end.minusDays(i), new BigDecimal(amount), "USD"));
        }
        return buckets;
    }

    @Test
    void spikeFiresAllThreeGates() {
        var end = LocalDate.of(2026, 9, 9);
        var buckets = constantSeries(27, "10.00", end.minusDays(1));
        buckets.add(new CostAnomalyEngine.DailyBucket(end, new BigDecimal("30.00"), "USD"));
        var anomalies = CostAnomalyEngine.detect("ORGANIZATION", "org:7", buckets,
                List.of(new CostAnomalyEngine.Contribution("MODEL", "m", new BigDecimal("20.00"))),
                new BigDecimal("5.00"));
        assertEquals(1, anomalies.size());
        var anomaly = anomalies.get(0);
        assertEquals(new BigDecimal("20.00"), anomaly.deltaAmount());
        assertTrue(Math.abs(anomaly.robustZScore()) >= 3.0);
        assertEquals(1, anomaly.drivers().size());
    }

    @Test
    void tinyAbsoluteDeltaIsNotMaterial() {
        var end = LocalDate.of(2026, 9, 9);
        var buckets = constantSeries(27, "0.01", end.minusDays(1));
        buckets.add(new CostAnomalyEngine.DailyBucket(end, new BigDecimal("0.03"), "USD"));
        var anomalies = CostAnomalyEngine.detect(
                "ORGANIZATION", "org:7", buckets, List.of(), new BigDecimal("5.00"));
        assertTrue(anomalies.isEmpty());
    }

    @Test
    void insufficientHistoryYieldsNothing() {
        var anomalies = CostAnomalyEngine.detect("ORGANIZATION", "org:7",
                constantSeries(10, "10.00", LocalDate.of(2026, 9, 9)), List.of(), BigDecimal.ZERO);
        assertTrue(anomalies.isEmpty());
    }

    @Test
    void mixedCurrencyIsRejected() {
        var buckets = constantSeries(15, "10.00", LocalDate.of(2026, 9, 9));
        buckets.set(3, new CostAnomalyEngine.DailyBucket(
                buckets.get(3).date(), new BigDecimal("10.00"), "EUR"));
        assertThrows(IllegalArgumentException.class, () -> CostAnomalyEngine.detect(
                "ORGANIZATION", "org:7", buckets, List.of(), BigDecimal.ZERO));
    }

    @Test
    void constantSeriesForecastsRunRate() {
        var buckets = constantSeries(20, "5.00", LocalDate.of(2026, 9, 9));
        var forecast = ForecastEngine.forecast(buckets, 10, LocalDate.of(2026, 9, 9));
        assertEquals("DAMPED_HOLT", forecast.method());
        assertTrue(forecast.projectedAmount().subtract(new BigDecimal("50.00")).abs()
                .compareTo(new BigDecimal("1.00")) < 0);
        assertEquals("USD", forecast.currency());
    }

    @Test
    void shortHistoryUsesFallback() {
        var buckets = constantSeries(5, "4.00", LocalDate.of(2026, 9, 9));
        var forecast = ForecastEngine.forecast(buckets, 7, LocalDate.of(2026, 9, 9));
        assertEquals("RECENT_RUN_RATE", forecast.method());
        assertEquals(0, forecast.projectedAmount().compareTo(new BigDecimal("28.00000000")));
        assertEquals("LOW", forecast.confidence());
    }

    @Test
    void budgetRiskSeparatesExposureFromProjection() {
        var assessment = BudgetRiskService.assess(new BigDecimal("60.00"), new BigDecimal("10.00"),
                new BigDecimal("20.00"), new BigDecimal("5.00"), new BigDecimal("100.00"), "USD");
        assertEquals(0, assessment.immediateExposure().compareTo(new BigDecimal("90.00")));
        assertEquals(0, assessment.projectedPeriodEnd().compareTo(new BigDecimal("75.00")));
        assertEquals("HIGH", assessment.risk());
        var over = BudgetRiskService.assess(new BigDecimal("90.00"), new BigDecimal("20.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"), "USD");
        assertEquals("OVER_BUDGET", over.risk());
    }

    @Test
    void savingsReplayUsesPricingSemantics() {
        var usage = Map.of("INPUT_TOKEN", new BigDecimal("1000000"), "OUTPUT_TOKEN", new BigDecimal("500000"));
        var current = Map.of(
                "INPUT_TOKEN", new SavingsEngine.PricedRate(1_000_000L, new BigDecimal("2.00")),
                "OUTPUT_TOKEN", new SavingsEngine.PricedRate(1_000_000L, new BigDecimal("8.00")));
        var candidate = Map.of(
                "INPUT_TOKEN", new SavingsEngine.PricedRate(1_000_000L, new BigDecimal("1.00")),
                "OUTPUT_TOKEN", new SavingsEngine.PricedRate(1_000_000L, new BigDecimal("4.00")));
        var comparison = SavingsEngine.compare(9L, 9L, 9L, usage, current, candidate);
        assertEquals(0, comparison.currentCost().compareTo(new BigDecimal("6.00000000")));
        assertEquals(0, comparison.candidateCost().compareTo(new BigDecimal("3.00000000")));
        assertEquals(0, comparison.potentialSaving().compareTo(new BigDecimal("3.00000000")));
        assertEquals(0, comparison.potentialSavingPercent().compareTo(new BigDecimal("50.0000")));
    }

    @Test
    void savingsRejectsDifferentLogicalModels() {
        assertThrows(IllegalArgumentException.class, () -> SavingsEngine.compare(
                9L, 9L, 10L, Map.of(), Map.of(), Map.of()));
    }
}
