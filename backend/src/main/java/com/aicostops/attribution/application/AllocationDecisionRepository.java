package com.aicostops.attribution.application;

import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationLine;
import java.util.List;
import java.util.Optional;

/**
 * Persistence of allocation decisions and their lines. This foundation only
 * inserts DRAFT rows and reads them back; confirm, supersede, and the
 * current-decision pointer mutation belong to the Group 3 confirm workflow
 * (#49).
 */
public interface AllocationDecisionRepository {

    long insertDraft(NewAllocationDecisionDraft draft);

    void insertLine(NewAllocationLine line);

    Optional<AllocationDecision> findByIdAndOrganization(
            long organizationId, long decisionId);

    List<AllocationLine> linesOfDecision(
            long organizationId, long decisionId);

    int countConfirmedForCharge(
            long organizationId, long chargeFactId);

    /** Locking read of one decision, org-scoped. */
    Optional<AllocationDecision> findByIdForUpdate(
            long organizationId, long decisionId);

    /**
     * Locking read of every DRAFT decision of one charge, ordered by decision
     * id ascending; the canonical lock order for superseding related drafts.
     */
    List<AllocationDecision> findDraftDecisionsByChargeForUpdate(
            long organizationId, long chargeFactId);

    /** Non-locking read of every decision of one charge, ordered by id ascending. */
    List<AllocationDecision> findDecisionsByCharge(
            long organizationId, long chargeFactId);

    /** Locking read of the lines of one decision, ordered by line index. */
    List<AllocationLine> linesOfDecisionForUpdate(
            long organizationId, long decisionId);

    /** Deletes every line of one decision (used for replace-lines semantics). */
    void deleteLinesOfDecision(
            long organizationId, long decisionId);

    /** DRAFT -> CONFIRMED; must affect exactly one row. */
    void confirmDecision(long organizationId, long decisionId);

    /** DRAFT -> SUPERSEDED; must affect exactly one row. Lines are preserved. */
    void supersedeDecision(long organizationId, long decisionId);
}
