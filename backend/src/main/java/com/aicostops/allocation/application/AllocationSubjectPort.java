package com.aicostops.allocation.application;

import com.aicostops.attribution.domain.AllocationSubjectType;
import java.math.BigDecimal;

/**
 * Source abstraction behind an allocation decision subject. The command
 * service routes every operation through the adapter of the decision's
 * {@link AllocationSubjectType}; charge-specific gates (confirmed-import
 * lineage, CLEAN review status) live in the charge adapter and are no-ops for
 * expenses.
 */
public interface AllocationSubjectPort {

    AllocationSubjectType subjectType();

    /**
     * Locks the source row and returns its allocation facts. Must be called
     * before the allocation decision (M3 lock order: source -> decision ->
     * lines), so the two subjects never cross-lock.
     */
    SubjectLoad loadForUpdate(long organizationId, long subjectId);

    /**
     * Confirm eligibility of an already-locked source. Charge: confirmed
     * import lineage, CLEAN review status, no current decision pointer.
     * Expense: APPROVED status, no current decision pointer.
     */
    void assertConfirmEligible(long organizationId, SubjectLoad load);

    /** Writes the current-decision pointer on the source row (confirm only). */
    void setCurrentDecisionPointer(long organizationId, long subjectId, long decisionId);

    /** Source facts; {@code status} is the review/claim status of the subject. */
    record SubjectLoad(
            long subjectId,
            BigDecimal amount,
            String currency,
            Long currentAllocationDecisionId,
            String status) {
    }
}