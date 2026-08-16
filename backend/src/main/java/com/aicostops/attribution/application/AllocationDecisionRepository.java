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
}
