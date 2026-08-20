package com.aicostops.ledger.application;

/** Audit seam for immutable Ledger posting events. */
public interface LedgerAuditPort {

    void chargePosted(long organizationId, long actorUserId, long postingId,
            long chargeFactId, long allocationDecisionId, int entryCount, String currency);

    void expensePosted(long organizationId, long actorUserId, long postingId,
            long expenseClaimId, long allocationDecisionId, int entryCount, String currency);

    void correctionPosted(long organizationId, long actorUserId, long postingId,
            long correctionGroupId, long targetEntryId, String mode, int entryCount,
            String currency);
}
