package com.aicostops.allocation.api;

import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationProposalView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;
import java.util.List;

/** HTTP response shapes of the allocation workflow; ids and money are strings. */
public final class AllocationDecisionResponses {

    private AllocationDecisionResponses() {
    }

    public record AllocationRuleTraceResponse(
            ApiId id,
            String ruleKey,
            int version,
            int priority) {

        public static AllocationRuleTraceResponse from(AllocationRuleTrace trace) {
            return new AllocationRuleTraceResponse(
                    ApiId.of(trace.allocationRuleId()),
                    trace.ruleKey(),
                    trace.version(),
                    trace.priority());
        }
    }

    public record AllocationLineResponse(
            ApiId id,
            int lineIndex,
            String allocatedAmount,
            String currency,
            ApiId projectId,
            ApiId costCenterId,
            ApiId teamId) {
    }

    public record AllocationDecisionResponse(
            ApiId id,
            String subjectType,
            ApiId chargeFactId,
            ApiId expenseClaimId,
            String source,
            String status,
            AllocationRuleTraceResponse allocationRule,
            ApiId createdByMemberId,
            Instant createdAt,
            List<AllocationLineResponse> lines) {

        public static AllocationDecisionResponse from(AllocationDecisionView view) {
            return new AllocationDecisionResponse(
                    ApiId.of(view.decision().id()),
                    view.decision().subjectType().name(),
                    view.decision().chargeFactId() == null
                            ? null
                            : ApiId.of(view.decision().chargeFactId()),
                    view.decision().expenseClaimId() == null
                            ? null
                            : ApiId.of(view.decision().expenseClaimId()),
                    view.decision().decisionSource().name(),
                    view.decision().status().name(),
                    view.ruleTrace() == null
                            ? null
                            : AllocationRuleTraceResponse.from(view.ruleTrace()),
                    view.decision().createdByMemberId() == null
                            ? null
                            : ApiId.of(view.decision().createdByMemberId()),
                    view.decision().createdAt(),
                    view.lines().stream()
                            .map(line -> new AllocationLineResponse(
                                    ApiId.of(line.id()),
                                    line.lineIndex(),
                                    line.allocatedAmount().toPlainString(),
                                    line.currency(),
                                    line.projectId() == null ? null : ApiId.of(line.projectId()),
                                    line.costCenterId() == null
                                            ? null
                                            : ApiId.of(line.costCenterId()),
                                    line.teamId() == null ? null : ApiId.of(line.teamId())))
                            .toList());
        }
    }

    public record AllocationProposalResponse(
            String status,
            AllocationDecisionResponse decision,
            AllocationRuleTraceResponse ruleTrace,
            String reason) {

        public static AllocationProposalResponse from(AllocationProposalView view) {
            return new AllocationProposalResponse(
                    view.status().name(),
                    view.decision() == null ? null : AllocationDecisionResponse.from(view.decision()),
                    view.ruleTrace() == null
                            ? null
                            : AllocationRuleTraceResponse.from(view.ruleTrace()),
                    view.reason() == null ? null : view.reason().name());
        }
    }
}
