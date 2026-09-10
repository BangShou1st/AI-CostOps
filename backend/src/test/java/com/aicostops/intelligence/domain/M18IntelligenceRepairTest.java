package com.aicostops.intelligence.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M18 P1: savings counterfactual + scoped budget risk regressions (pure domain). */
class M18IntelligenceRepairTest {

    @Test
    void unusedCheaperCandidateIsRecommendableOnSameUsageVector() {
        var usage = Map.of("input_tokens", 1_000_000L, "output_tokens", 500_000L);
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
        var usage = Map.of("input_tokens", 100L);
        var rates = Map.of("input_tokens", new SavingsEngine.PricedRate(1, new BigDecimal("1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> SavingsEngine.compare(1L, 1L, 2L, usage, rates, rates));
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
