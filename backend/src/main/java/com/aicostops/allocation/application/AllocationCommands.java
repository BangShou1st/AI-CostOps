package com.aicostops.allocation.application;

import com.aicostops.attribution.domain.AllocationRuleMatchType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Parsed command inputs of the allocation workflow. Amounts are already
 * canonical scale-8 values; exactly-one-target is already enforced.
 */
public final class AllocationCommands {

    private AllocationCommands() {
    }

    public record AllocationLineCommand(
            BigDecimal allocatedAmount,
            String currency,
            Long projectId,
            Long costCenterId,
            Long teamId) {
    }

    public record ManualDraftCommand(List<AllocationLineCommand> lines) {
        public ManualDraftCommand {
            lines = List.copyOf(lines);
        }
    }

    /** Definition of a new immutable rule version (the key comes from the path). */
    public record RuleDefinitionCommand(
            String name,
            String providerCode,
            Long providerAccountId,
            AllocationRuleMatchType matchHintType,
            String matchValue,
            int priority,
            Long targetProjectId,
            Long targetCostCenterId,
            Long targetTeamId,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }
}
