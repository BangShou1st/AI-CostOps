package com.aicostops.intelligence.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M18 P1: savings counterfactual + scoped budget risk regressions (pure domain). */
class M18IntelligenceRepairTest {

    @Test
    void unusedCheaperCandidateIsRecommendableOnSameUsageVector() {
        var usage = Map.of("input_tokens", new BigDecimal("1000000"), "output_tokens", new BigDecimal("500000"));
        var currentRates = Map.of("input_tokens", new SavingsEngine.PricedRate(1_000_000, new BigDecimal("10.00")),
                "output_tokens", new SavingsEngine.PricedRate(1_000_000, new BigDecimal("30.00")));
        var candidateRates = Map.of("input_tokens", new SavingsEngine.PricedRate(1_000_000, new BigDecimal("2.00")),
                "output_tokens", new SavingsEngine.PricedRate(1_000_000, new BigDecimal("6.00")));
        var comparison = SavingsEngine.compare(7L, 7L, 7L, usage, currentRates, candidateRates);
        assertTrue(comparison.potentialSaving().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(comparison.potentialSavingPercent().compareTo(new BigDecimal("5")) >= 0);
        assertEquals(0, comparison.candidateCost().compareTo(new BigDecimal("5.00")));
    }

    @Test
    void savingsRequiresSameLogicalModel() {
        var usage = Map.of("input_tokens", new BigDecimal("100"));
        var rates = Map.of("input_tokens", new SavingsEngine.PricedRate(1, new BigDecimal("1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> SavingsEngine.compare(1L, 1L, 2L, usage, rates, rates));
    }

    @Test
    void fractionalUsageIsNotTruncated() {
        var usage = Map.of("input_tokens", new BigDecimal("0.50000000"));
        var rates = Map.of("input_tokens", new SavingsEngine.PricedRate(1, new BigDecimal("2.00")));
        assertEquals(0, SavingsEngine.replay(usage, rates).compareTo(new BigDecimal("1.00")));
    }

    @Test
    void fractionalUsageKeepsPrecision() {
        var usage = Map.of("input_tokens", new BigDecimal("1.12500000"));
        var rates = Map.of("input_tokens", new SavingsEngine.PricedRate(1, new BigDecimal("2.00")));
        assertEquals(0, SavingsEngine.replay(usage, rates).compareTo(new BigDecimal("2.25")));
    }

    @Test
    void budgetRiskMathHoldsForScopedGrains() {
        var assessment = BudgetRiskService.assess(new BigDecimal("80.00"), new BigDecimal("5.00"),
                new BigDecimal("2.00"), new BigDecimal("10.00"), new BigDecimal("100.00"), "USD");
        assertEquals("HIGH", assessment.risk());
        assertEquals(0, assessment.immediateExposure().compareTo(new BigDecimal("87.00")));
        assertEquals(0, assessment.projectedPeriodEnd().compareTo(new BigDecimal("95.00")));
    }
}
