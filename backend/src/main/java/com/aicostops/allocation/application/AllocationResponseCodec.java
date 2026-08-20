package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationProposalView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
import com.aicostops.allocation.application.AllocationReadModels.NoMatchReason;
import com.aicostops.allocation.application.AllocationReadModels.ProposalStatus;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationDecisionStatus;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.attribution.domain.AllocationRule;
import com.aicostops.attribution.domain.AllocationSubjectType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes allocation command responses into {@code api_idempotency}
 * response bodies and back. Money is stored as plain scale-8 strings so the
 * replay is byte-stable and never passes through floating point.
 */
@Component
public class AllocationResponseCodec {

    private final ObjectMapper objectMapper;

    public AllocationResponseCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record DecisionJson(
            long id,
            long organizationId,
            String subjectType,
            Long chargeFactId,
            Long expenseClaimId,
            String decisionSource,
            Long allocationRuleId,
            String status,
            Long createdByMemberId,
            String createdAt,
            RuleTraceJson ruleTrace,
            List<LineJson> lines) {
    }

    public record LineJson(
            Long id,
            int lineIndex,
            String allocatedAmount,
            String currency,
            Long projectId,
            Long costCenterId,
            Long teamId,
            String createdAt) {
    }

    public record RuleTraceJson(
            long allocationRuleId,
            String ruleKey,
            int version,
            int priority) {
    }

    public record ProposalJson(
            String status,
            DecisionJson decision,
            RuleTraceJson ruleTrace,
            String reason) {
    }

    public String decisionToJson(AllocationDecisionView view) {
        return write(decisionJson(view));
    }

    public AllocationDecisionView decisionFromJson(String json) {
        return toView(read(json, DecisionJson.class));
    }

    public String proposalToJson(AllocationProposalView view) {
        return write(new ProposalJson(
                view.status().name(),
                view.decision() == null ? null : decisionJson(view.decision()),
                view.ruleTrace() == null ? null : ruleTraceJson(view.ruleTrace()),
                view.reason() == null ? null : view.reason().name()));
    }

    public AllocationProposalView proposalFromJson(String json) {
        var stored = read(json, ProposalJson.class);
        return new AllocationProposalView(
                ProposalStatus.valueOf(stored.status()),
                stored.decision() == null ? null : toView(stored.decision()),
                stored.ruleTrace() == null ? null : toTrace(stored.ruleTrace()),
                stored.reason() == null ? null : NoMatchReason.valueOf(stored.reason()));
    }

    public String ruleToJson(AllocationRule rule) {
        return write(rule);
    }

    public AllocationRule ruleFromJson(String json) {
        return read(json, AllocationRule.class);
    }

    private static DecisionJson decisionJson(AllocationDecisionView view) {
        return new DecisionJson(
                view.decision().id(),
                view.decision().organizationId(),
                view.decision().subjectType().name(),
                view.decision().chargeFactId(),
                view.decision().expenseClaimId(),
                view.decision().decisionSource().name(),
                view.decision().allocationRuleId(),
                view.decision().status().name(),
                view.decision().createdByMemberId(),
                view.decision().createdAt().toString(),
                view.ruleTrace() == null ? null : ruleTraceJson(view.ruleTrace()),
                view.lines().stream().map(AllocationResponseCodec::lineJson).toList());
    }

    private static LineJson lineJson(AllocationLine line) {
        return new LineJson(
                line.id(),
                line.lineIndex(),
                line.allocatedAmount().toPlainString(),
                line.currency(),
                line.projectId(),
                line.costCenterId(),
                line.teamId(),
                line.createdAt().toString());
    }

    private static RuleTraceJson ruleTraceJson(AllocationRuleTrace trace) {
        return new RuleTraceJson(
                trace.allocationRuleId(), trace.ruleKey(), trace.version(), trace.priority());
    }

    private static AllocationDecisionView toView(DecisionJson stored) {
        var decision = new AllocationDecision(
                stored.id(),
                stored.organizationId(),
                AllocationSubjectType.valueOf(stored.subjectType()),
                stored.chargeFactId(),
                stored.expenseClaimId(),
                AllocationDecisionSource.valueOf(stored.decisionSource()),
                stored.allocationRuleId(),
                AllocationDecisionStatus.valueOf(stored.status()),
                stored.createdByMemberId(),
                Instant.parse(stored.createdAt()));
        var lines = stored.lines().stream()
                .map(line -> new AllocationLine(
                        line.id() == null ? -1L : line.id(),
                        stored.organizationId(),
                        stored.id(),
                        line.lineIndex(),
                        new BigDecimal(line.allocatedAmount()),
                        line.currency(),
                        line.projectId(),
                        line.costCenterId(),
                        line.teamId(),
                        Instant.parse(line.createdAt())))
                .toList();
        return new AllocationDecisionView(
                decision, lines, stored.ruleTrace() == null ? null : toTrace(stored.ruleTrace()));
    }

    private static AllocationRuleTrace toTrace(RuleTraceJson stored) {
        return new AllocationRuleTrace(
                stored.allocationRuleId(), stored.ruleKey(), stored.version(), stored.priority());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Allocation response serialization failed", failure);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception failure) {
            throw new IllegalStateException("Allocation response deserialization failed", failure);
        }
    }
}
