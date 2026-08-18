package com.aicostops.budget.application;

import com.aicostops.budget.application.CommitmentIdempotencyStore.IdempotencyDecision;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Commitment idempotency helper: key validation, canonical request hashes,
 * and delegation to {@link CommitmentIdempotencyStore}. The request hash
 * covers the operation + organization/actor + canonical body (including the
 * exact scale-8 amounts, expectedVersion, and comment), so a replayed key
 * with a different body is a 409 conflict.
 */
@Component
public final class CommitmentIdempotency {

    public static final String OPERATION_COMMITMENT_REQUEST = "COMMITMENT_REQUEST";
    public static final String OPERATION_COMMITMENT_APPROVE = "COMMITMENT_APPROVE";
    public static final String OPERATION_COMMITMENT_REJECT = "COMMITMENT_REJECT";
    public static final String OPERATION_COMMITMENT_CANCEL = "COMMITMENT_CANCEL";
    public static final String OPERATION_COMMITMENT_RELEASE = "COMMITMENT_RELEASE";

    private static final int MAX_KEY_CHARACTERS = 200;

    private final CommitmentIdempotencyStore store;

    public CommitmentIdempotency(CommitmentIdempotencyStore store) {
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

    public String requestRequestHash(long organizationId, long actorMemberId, long budgetId,
            BigDecimal requestedAmount, String currency) {
        var canonical = "operation=" + OPERATION_COMMITMENT_REQUEST
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nbudgetId=" + budgetId
                + "\nrequestedAmount=" + requestedAmount.toPlainString()
                + "\ncurrency=" + currency;
        return sha256Hex(canonical);
    }

    public String approveRequestHash(long organizationId, long actorMemberId, long commitmentId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_COMMITMENT_APPROVE
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ncommitmentId=" + commitmentId
                + "\nexpectedVersion=" + expectedVersion;
        return sha256Hex(canonical);
    }

    public String rejectRequestHash(long organizationId, long actorMemberId, long commitmentId,
            long expectedVersion, String comment) {
        var canonical = "operation=" + OPERATION_COMMITMENT_REJECT
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ncommitmentId=" + commitmentId
                + "\nexpectedVersion=" + expectedVersion
                + "\ncomment=" + comment;
        return sha256Hex(canonical);
    }

    public String cancelRequestHash(long organizationId, long actorMemberId, long commitmentId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_COMMITMENT_CANCEL
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ncommitmentId=" + commitmentId
                + "\nexpectedVersion=" + expectedVersion;
        return sha256Hex(canonical);
    }

    public String releaseRequestHash(long organizationId, long actorMemberId, long commitmentId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_COMMITMENT_RELEASE
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ncommitmentId=" + commitmentId
                + "\nexpectedVersion=" + expectedVersion;
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
