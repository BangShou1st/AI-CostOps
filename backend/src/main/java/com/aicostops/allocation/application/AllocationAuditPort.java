package com.aicostops.allocation.application;

import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationSubjectType;

/**
 * Audit port of the allocation workflow. Implementations must append the
 * audit event inside the caller's transaction so any audit write failure
 * rolls the whole command back.
 */
public interface AllocationAuditPort {

    /**
     * Appends {@code ALLOCATION_DECISION_CONFIRMED} with the decision's subject
     * type and subject id (CHARGE_FACT -> chargeFactId, EXPENSE_CLAIM ->
     * expenseClaimId). Metadata is limited to safe fields: decision id, source,
     * optional rule id, line count, and currency — never provider raw values
     * or secrets.
     */
    void decisionConfirmed(
            long organizationId,
            long actorUserId,
            long decisionId,
            AllocationSubjectType subjectType,
            long subjectId,
            AllocationDecisionSource decisionSource,
            Long allocationRuleId,
            int lineCount,
            String currency);
}