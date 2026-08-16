package com.aicostops.cost.review.application;

/**
 * Reservation/replay boundary against the {@code api_idempotency} table for
 * duplicate review commands. Must be called inside the caller's transaction.
 */
public interface DuplicateReviewIdempotencyStore {

    IdempotencyDecision reserve(
            long organizationId,
            long actorMemberId,
            String operation,
            String rawKey,
            String requestHash);

    void finalize(
            long reservationId,
            int responseStatus,
            String responseBody);

    record IdempotencyDecision(
            long id,
            boolean replay,
            int responseStatus,
            String responseBody) {
    }
}
