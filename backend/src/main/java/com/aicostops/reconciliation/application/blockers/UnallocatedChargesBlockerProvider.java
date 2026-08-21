package com.aicostops.reconciliation.application.blockers;

import com.aicostops.cost.application.AllocationCloseBlockerPort;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class UnallocatedChargesBlockerProvider implements CloseBlockerProvider {
    private static final int SAMPLE_LIMIT = 20;
    private final AllocationCloseBlockerPort allocations;

    public UnallocatedChargesBlockerProvider(AllocationCloseBlockerPort allocations) {
        this.allocations = allocations;
    }

    @Override public CloseBlockerCode code() { return CloseBlockerCode.UNALLOCATED_CHARGES; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var count = allocations.countUnallocatedCleanCharges(
                context.organizationId(), context.periodStart(), context.periodEnd());
        var summary = Map.<String, Object>of(
                "sampleChargeFactIds", allocations.sampleUnallocatedChargeIds(
                        context.organizationId(), context.periodStart(), context.periodEnd(), SAMPLE_LIMIT));
        return count == 0 ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), count, summary);
    }
}
