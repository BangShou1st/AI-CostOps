package com.aicostops.allocation.application;

import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.attribution.domain.AllocationSubjectType;
import java.util.List;

/** Posting-specific allocation read/lock contract. */
public interface AllocationPostingPort {

    ConfirmedAllocation load(long organizationId, long decisionId);

    ConfirmedAllocation lockConfirmed(
            long organizationId,
            long decisionId,
            AllocationSubjectType subjectType,
            long subjectId);

    record ConfirmedAllocation(AllocationDecision decision, List<AllocationLine> lines) {
        public ConfirmedAllocation {
            lines = List.copyOf(lines);
        }
    }
}
