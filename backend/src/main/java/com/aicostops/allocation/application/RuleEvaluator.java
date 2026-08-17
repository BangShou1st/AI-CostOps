package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationReadModels.NoMatchReason;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper.HintRow;
import com.aicostops.attribution.domain.AllocationRule;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Deterministic rule selection for allocation proposals.
 *
 * <p>The evaluator never invents matching: it consumes only the canonical
 * attribution hint evidence and the exact rule snapshot returned by the
 * repository, then picks the winning rule with the frozen total order
 * {@code priority ASC -> ruleKey ASC -> version DESC -> id ASC}. The same
 * comparator is mirrored by the repository ORDER BY, so the winner is
 * stable for identical inputs and rule snapshots.
 */
public final class RuleEvaluator {

    private static final Set<String> LEGAL_MATCH_TYPES = Set.of(
            "PROVIDER_API_KEY", "PROVIDER_PROJECT", "PROVIDER_USER");

    private RuleEvaluator() {
    }

    /** Frozen tie-break; must stay identical to the repository ORDER BY. */
    public static final Comparator<AllocationRule> TIE_BREAK = Comparator
            .comparingInt(AllocationRule::priority)
            .thenComparing(AllocationRule::ruleKey)
            .thenComparing(Comparator.comparingInt(AllocationRule::version).reversed())
            .thenComparingLong(AllocationRule::id);

    /**
     * Picks the winning rule from the already-filtered candidates, or returns
     * a deterministic no-match reason. {@code null} period start has no
     * effective time to evaluate against; hints of a type the rules cannot
     * match (for example EMPLOYEE_SELECTION) and empty candidate lists yield
     * {@code NO_RULE_MATCH}.
     */
    public static Evaluation evaluate(
            com.aicostops.allocation.application.AllocationReadModels.AllocationChargeRow charge,
            HintRow hint,
            List<AllocationRule> candidates) {
        if (charge.periodStart() == null) {
            return Evaluation.noMatch(NoMatchReason.NO_EFFECTIVE_TIME);
        }
        if (hint == null || !LEGAL_MATCH_TYPES.contains(hint.hintType())) {
            return Evaluation.noMatch(NoMatchReason.NO_RULE_MATCH);
        }
        return candidates.stream()
                .sorted(TIE_BREAK)
                .findFirst()
                .map(Evaluation::match)
                .orElseGet(() -> Evaluation.noMatch(NoMatchReason.NO_RULE_MATCH));
    }

    public record Evaluation(AllocationRule winningRule, NoMatchReason noMatchReason) {

        public boolean matched() {
            return winningRule != null;
        }

        public static Evaluation match(AllocationRule rule) {
            return new Evaluation(rule, null);
        }

        public static Evaluation noMatch(NoMatchReason reason) {
            return new Evaluation(null, reason);
        }
    }
}
