package com.aicostops.ledger.application;

/** Reservation/replay boundary for the immutable correction command. */
public interface LedgerCorrectionIdempotencyStore {

    IdempotencyDecision reserve(long organizationId, long actorMemberId, String operation,
            String rawKey, String requestHash);

    void finalize(long reservationId, int responseStatus, String responseBody);

    record IdempotencyDecision(long id, boolean replay, int responseStatus, String responseBody) {
    }
}
