package com.aicostops.reconciliation.application.blockers;

import com.aicostops.cost.review.application.DuplicateCloseBlockerPort;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class UnresolvedDuplicatesBlockerProvider implements CloseBlockerProvider {
    private static final int SAMPLE_LIMIT = 20;
    private final DuplicateCloseBlockerPort duplicates;

    public UnresolvedDuplicatesBlockerProvider(DuplicateCloseBlockerPort duplicates) {
        this.duplicates = duplicates;
    }

    @Override public CloseBlockerCode code() { return CloseBlockerCode.UNRESOLVED_DUPLICATES; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var count = duplicates.countUnresolvedDuplicates(
                context.organizationId(), context.periodStart(), context.periodEnd());
        var summary = Map.<String, Object>of(
                "sampleDuplicateCandidateIds", duplicates.sampleUnresolvedDuplicateIds(
                        context.organizationId(), context.periodStart(), context.periodEnd(), SAMPLE_LIMIT));
        return count == 0 ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), count, summary);
    }
}
