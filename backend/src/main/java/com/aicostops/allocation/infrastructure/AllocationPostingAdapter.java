package com.aicostops.allocation.infrastructure;

import com.aicostops.allocation.application.AllocationPostingPort;
import com.aicostops.allocation.application.AllocationPostingPort.ConfirmedAllocation;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationDecisionStatus;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.attribution.infrastructure.AllocationDecisionMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Owns confirmed decision/line reads without exposing attribution mappers. */
@Component
public class AllocationPostingAdapter implements AllocationPostingPort {

    private final AllocationDecisionMapper mapper;

    public AllocationPostingAdapter(AllocationDecisionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ConfirmedAllocation load(long organizationId, long decisionId) {
        var decision = mapper.selectByIdAndOrganization(organizationId, decisionId);
        var lines = decision == null ? java.util.List.<com.aicostops.attribution.domain.AllocationLine>of()
                : mapper.selectLinesOfDecision(organizationId, decisionId);
        return validate(decision, lines, null, 0);
    }

    @Override
    public ConfirmedAllocation lockConfirmed(long organizationId, long decisionId,
            AllocationSubjectType subjectType, long subjectId) {
        var decision = mapper.selectByIdForUpdate(organizationId, decisionId);
        var lines = decision == null ? java.util.List.<com.aicostops.attribution.domain.AllocationLine>of()
                : mapper.selectLinesOfDecisionForUpdate(organizationId, decisionId);
        return validate(decision, lines, subjectType, subjectId);
    }

    private static ConfirmedAllocation validate(AllocationDecision decision,
            java.util.List<com.aicostops.attribution.domain.AllocationLine> lines,
            AllocationSubjectType expectedSubjectType, long expectedSubjectId) {
        if (decision == null) {
            throw notFound();
        }
        if (decision.status() != AllocationDecisionStatus.CONFIRMED) {
            throw notEligible("The allocation decision must be CONFIRMED.");
        }
        if (expectedSubjectType != null && (decision.subjectType() != expectedSubjectType
                || !subjectIdMatches(decision, expectedSubjectType, expectedSubjectId))) {
            throw notEligible("The allocation decision does not belong to the posting source.");
        }
        if (lines.isEmpty()) {
            throw notEligible("A confirmed allocation must contain at least one line.");
        }
        return new ConfirmedAllocation(decision, lines);
    }

    private static boolean subjectIdMatches(AllocationDecision decision,
            AllocationSubjectType subjectType, long subjectId) {
        return subjectType == AllocationSubjectType.CHARGE_FACT
                ? Long.valueOf(subjectId).equals(decision.chargeFactId())
                : Long.valueOf(subjectId).equals(decision.expenseClaimId());
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Allocation decision not found",
                "The allocation decision is not available in the current organization.");
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Allocation is not postable", detail);
    }
}
