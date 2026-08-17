package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.application.AllocationReadModels.AllocationChargeRow;
import com.aicostops.allocation.application.AllocationReadModels.NoMatchReason;
import com.aicostops.allocation.application.RuleEvaluator;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper.HintRow;
import com.aicostops.attribution.domain.AllocationRule;
import com.aicostops.attribution.domain.AllocationRuleMatchType;
import com.aicostops.attribution.domain.AllocationRuleStatus;
import com.aicostops.cost.domain.ReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic rule selection: the frozen tie-break total order and the
 * safe no-match reasons. The comparator must stay identical to the
 * repository ORDER BY.
 */
class RuleEvaluatorTest {

    private static final Instant PERIOD_START = Instant.parse("2026-01-15T00:00:00Z");

    @Test
    void picksLowerPriorityFirst() {
        var rules = List.of(rule(1, "a", 1, 10), rule(2, "b", 1, 5));
        assertThat(RuleEvaluator.evaluate(charge(PERIOD_START), hint(), rules).winningRule().id())
                .isEqualTo(2);
    }

    @Test
    void tieBreaksByRuleKeyAscending() {
        var rules = List.of(rule(1, "zeta", 1, 5), rule(2, "alpha", 1, 5));
        assertThat(RuleEvaluator.evaluate(charge(PERIOD_START), hint(), rules).winningRule().id())
                .isEqualTo(2);
    }

    @Test
    void tieBreaksByVersionDescending() {
        var rules = List.of(rule(1, "a", 1, 5), rule(2, "a", 2, 5), rule(3, "a", 3, 5));
        assertThat(RuleEvaluator.evaluate(charge(PERIOD_START), hint(), rules).winningRule().id())
                .isEqualTo(3);
    }

    @Test
    void finalTieBreakIsIdAscending() {
        var rules = List.of(rule(10, "a", 1, 5), rule(5, "a", 1, 5), rule(7, "a", 1, 5));
        assertThat(RuleEvaluator.evaluate(charge(PERIOD_START), hint(), rules).winningRule().id())
                .isEqualTo(5);
    }

    @Test
    void sortedOrderIsDeterministicForIdenticalInputs() {
        var rules = List.of(rule(1, "b", 1, 5), rule(2, "a", 1, 5), rule(3, "a", 2, 5));
        var first = RuleEvaluator.evaluate(charge(PERIOD_START), hint(), rules).winningRule().id();
        var second = RuleEvaluator.evaluate(charge(PERIOD_START), hint(), rules).winningRule().id();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void nullPeriodStartYieldsNoEffectiveTime() {
        var evaluation = RuleEvaluator.evaluate(charge(null), hint(), List.of(rule(1, "a", 1, 5)));
        assertThat(evaluation.matched()).isFalse();
        assertThat(evaluation.noMatchReason()).isEqualTo(NoMatchReason.NO_EFFECTIVE_TIME);
    }

    @Test
    void nullHintYieldsNoRuleMatch() {
        var evaluation = RuleEvaluator.evaluate(charge(PERIOD_START), null, List.of(rule(1, "a", 1, 5)));
        assertThat(evaluation.matched()).isFalse();
        assertThat(evaluation.noMatchReason()).isEqualTo(NoMatchReason.NO_RULE_MATCH);
    }

    @Test
    void employeeSelectionHintIsNeverMatched() {
        var evaluation = RuleEvaluator.evaluate(charge(PERIOD_START),
                new HintRow("EMPLOYEE_SELECTION", "whatever"), List.of(rule(1, "a", 1, 5)));
        assertThat(evaluation.matched()).isFalse();
        assertThat(evaluation.noMatchReason()).isEqualTo(NoMatchReason.NO_RULE_MATCH);
    }

    @Test
    void emptyCandidatesYieldNoRuleMatch() {
        var evaluation = RuleEvaluator.evaluate(charge(PERIOD_START), hint(), List.of());
        assertThat(evaluation.matched()).isFalse();
        assertThat(evaluation.noMatchReason()).isEqualTo(NoMatchReason.NO_RULE_MATCH);
    }

    private static HintRow hint() {
        return new HintRow("PROVIDER_PROJECT", "alpha-project");
    }

    private static AllocationChargeRow charge(Instant periodStart) {
        return new AllocationChargeRow(
                1, 1, 10, 0, "GLM", new BigDecimal("10.00000000"), "CNY",
                periodStart, periodStart, ReviewStatus.CLEAN, null, null);
    }

    private static AllocationRule rule(long id, String ruleKey, int version, int priority) {
        return new AllocationRule(
                id, 1, ruleKey, version, "Rule " + id, "GLM", null,
                AllocationRuleMatchType.PROVIDER_PROJECT, "alpha-project", priority,
                1L, null, null,
                Instant.parse("2020-01-01T00:00:00Z"), null,
                AllocationRuleStatus.ACTIVE, 1, Instant.parse("2020-01-01T00:00:00Z"));
    }
}
