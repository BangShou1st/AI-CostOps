package com.aicostops.allocation.application;

import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.cost.domain.ReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read models of the allocation workflow. Money is carried as scale-8
 * {@link BigDecimal} and only stringified at the HTTP boundary.
 */
public final class AllocationReadModels {

    private AllocationReadModels() {
    }

    /** Workflow view of one charge: canonical facts plus allocation state. */
    public record AllocationChargeRow(
            long id,
            long organizationId,
            long rawRecordId,
            int factIndex,
            String providerCode,
            BigDecimal amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus,
            Long duplicateOfChargeId,
            Long currentAllocationDecisionId) {
    }

    /** Confirmed import lineage of a charge; provider account is derivable. */
    public record ChargeLineage(boolean confirmedImport, Long providerAccountId) {
    }

    /** Immutable identity of the rule version that produced a RULE decision. */
    public record AllocationRuleTrace(
            long allocationRuleId,
            String ruleKey,
            int version,
            int priority) {
    }

    /** One allocation decision with its lines and optional rule trace. */
    public record AllocationDecisionView(
            AllocationDecision decision,
            List<AllocationLine> lines,
            AllocationRuleTrace ruleTrace) {

        public AllocationDecisionView {
            lines = List.copyOf(lines);
        }
    }

    public enum ProposalStatus {
        CREATED, REUSED, NO_MATCH
    }

    /** Safe, deterministic no-match reasons; raw hints are never returned. */
    public enum NoMatchReason {
        NO_EFFECTIVE_TIME, NO_RULE_MATCH
    }

    /** Outcome of an allocation-proposal run. */
    public record AllocationProposalView(
            ProposalStatus status,
            AllocationDecisionView decision,
            AllocationRuleTrace ruleTrace,
            NoMatchReason reason) {
    }
}
