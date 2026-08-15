package com.aicostops.ingestion.application;

import com.aicostops.ingestion.infrastructure.ImportCommandIdempotencyMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reservation/replay helper for idempotent Import workflow commands, bound to the
 * existing {@code api_idempotency} table.
 *
 * <p>The raw Idempotency-Key header is never stored: the natural key column keeps
 * {@code lowercaseHex(SHA-256(exact UTF-8 key))}, so the table's case-insensitive
 * collation cannot conflate distinct caller keys. The request hash covers
 * operation + organization/actor context + ImportBatch id + empty body and stays
 * a separate column. A provisional {@code response_status=0} row exists only
 * uncommitted inside the command transaction; a committed provisional row is an
 * invariant violation.
 */
@Service
public class ImportCommandIdempotency {

    public static final String OPERATION_RETRY = "IMPORT_RETRY";
    public static final String OPERATION_CANCEL = "IMPORT_CANCEL";

    private static final int MAX_KEY_CHARACTERS = 200;

    private final ImportCommandIdempotencyMapper mapper;
    private final Clock clock;

    public ImportCommandIdempotency(ImportCommandIdempotencyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Outcome of a reservation attempt inside the caller's transaction. */
    public record IdempotencyDecision(long id, boolean replay, int responseStatus, String responseBody) {
    }

    /** 64-char lowercase hex SHA-256 fingerprint of the exact UTF-8 key value. */
    public static String keyFingerprint(String rawKey) {
        return sha256Hex(rawKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Canonical request hash; the M2 command body is always the empty object. */
    public static String requestHash(String operation, long orgId, long actorMemberId, long importBatchId) {
        var canonical = "operation=" + operation
                + "\norgId=" + orgId
                + "\nactorMemberId=" + actorMemberId
                + "\nimportBatchId=" + importBatchId
                + "\nbody={}";
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** HTTP-bound validation; must run before any database mutation. */
    public static void validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_CHARACTERS) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Idempotency-Key is invalid",
                    "Idempotency-Key must be a nonblank value of at most 200 characters.");
        }
    }

    /**
     * Reserves the key or replays the stored completed response. Must be called
     * inside the caller's transaction, before any state mutation.
     */
    public IdempotencyDecision reserve(
            long orgId, long actorMemberId, String operation, String rawKey, String requestHash) {
        var fingerprint = keyFingerprint(rawKey);
        try {
            mapper.insertProvisional(orgId, actorMemberId, operation, fingerprint, requestHash, clock.instant());
            return new IdempotencyDecision(mapper.lastInsertId(), false, 0, null);
        } catch (DuplicateKeyException concurrent) {
            // The winner committed while we were waiting: read the committed row
            // with a locking current read, never the stale consistent snapshot.
            var existing = mapper.findByNaturalKeyForUpdate(orgId, actorMemberId, operation, fingerprint);
            if (existing == null) {
                throw concurrent;
            }
            if (existing.responseStatus() == 0) {
                throw new IllegalStateException(
                        "A committed provisional idempotency row is an invariant violation");
            }
            if (!existing.requestHash().equals(requestHash)) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Idempotency key conflict",
                        "The idempotency key was already used for a different request.");
            }
            return new IdempotencyDecision(existing.id(), true, existing.responseStatus(),
                    existing.responseBody());
        }
    }

    /** Replaces the provisional row with the final response inside the same transaction. */
    public void finalize(long id, int responseStatus, String responseBody) {
        if (mapper.finalize(id, responseStatus, responseBody) != 1) {
            throw new IllegalStateException("Idempotency finalization must update exactly one row");
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
