package com.aicostops.allocation.application;

import com.aicostops.attribution.domain.AllocationDecisionSource;

/**
 * Audit port of the allocation workflow. Implementations must append the
 * audit event inside the caller's transaction so any audit write failure
 * rolls the whole command back.
 */
public interface AllocationAuditPort {

    /**
     * Appends {@code ALLOCATION_DECISION_CONFIRMED} with subject type
     * {@code CHARGE_FACT} and subject id {@code chargeFactId}. Metadata is
     * limited to safe fields: decision id, source, optional rule id, line
     * count, and currency — never provider raw values or secrets.
     */
    void decisionConfirmed(
            long organizationId,
            long actorUserId,
            long decisionId,
            long chargeFactId,
            AllocationDecisionSource decisionSource,
            Long allocationRuleId,
            int lineCount,
            String currency);
}
