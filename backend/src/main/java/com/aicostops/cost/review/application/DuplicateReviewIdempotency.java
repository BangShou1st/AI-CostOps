package com.aicostops.cost.review.application;

import com.aicostops.cost.review.application.DuplicateReviewIdempotencyStore.IdempotencyDecision;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Keep/Exclude idempotency helper: key validation, canonical request hashes,
 * and delegation to {@link DuplicateReviewIdempotencyStore}. The request hash
 * covers operation + organization/actor + candidate (+ excluded charge for
 * exclude); the raw key is fingerprinted by the store without trimming.
 */
@Component
public final class DuplicateReviewIdempotency {

    public static final String OPERATION_KEEP = "DUPLICATE_CANDIDATE_KEEP";
    public static final String OPERATION_EXCLUDE = "DUPLICATE_CANDIDATE_EXCLUDE";

    private static final int MAX_KEY_CHARACTERS = 200;

    private final DuplicateReviewIdempotencyStore store;

    public DuplicateReviewIdempotency(DuplicateReviewIdempotencyStore store) {
        this.store = store;
    }

    /** HTTP-bound validation; must run before any database mutation. */
    public static void validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_CHARACTERS) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Idempotency-Key is invalid",
                    "Idempotency-Key must be a nonblank value of at most 200 characters.");
        }
    }

    public String keepRequestHash(long organizationId, long actorMemberId, long candidateId) {
        var canonical = "operation=" + OPERATION_KEEP
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ncandidateId=" + candidateId
                + "\nbody={}";
        return sha256Hex(canonical);
    }

    public String excludeRequestHash(long organizationId, long actorMemberId, long candidateId,
            long excludedChargeFactId) {
        var canonical = "operation=" + OPERATION_EXCLUDE
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ncandidateId=" + candidateId
                + "\nexcludedChargeFactId=" + excludedChargeFactId;
        return sha256Hex(canonical);
    }

    public IdempotencyDecision reserve(long organizationId, long actorMemberId, String operation,
            String rawKey, String requestHash) {
        return store.reserve(organizationId, actorMemberId, operation, rawKey, requestHash);
    }

    public void finalize(long reservationId, int responseStatus, String responseBody) {
        store.finalize(reservationId, responseStatus, responseBody);
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
