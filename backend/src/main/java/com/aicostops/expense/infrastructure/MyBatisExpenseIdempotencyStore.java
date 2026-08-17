package com.aicostops.expense.infrastructure;

import com.aicostops.expense.application.ExpenseIdempotencyStore;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Expense reservation/replay against {@code api_idempotency}. The raw key is
 * never stored: the natural key keeps {@code hex(SHA-256(exact UTF-8 key))},
 * so distinct caller keys never conflate and the table's collation cannot
 * merge them either.
 */
@Component
public class MyBatisExpenseIdempotencyStore implements ExpenseIdempotencyStore {

    private final ExpenseIdempotencyMapper mapper;
    private final Clock clock;

    public MyBatisExpenseIdempotencyStore(ExpenseIdempotencyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 64-char lowercase hex SHA-256 fingerprint of the exact raw key value. */
    public static String keyFingerprint(String rawKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    @Override
    public IdempotencyDecision reserve(long organizationId, long actorMemberId, String operation,
            String rawKey, String requestHash) {
        var fingerprint = keyFingerprint(rawKey);
        try {
            mapper.insertProvisional(organizationId, actorMemberId, operation, fingerprint,
                    requestHash, clock.instant());
            return new IdempotencyDecision(mapper.lastInsertId(), false, 0, null);
        } catch (DuplicateKeyException concurrent) {
            // The winner committed while we were waiting: use a locking current
            // read, never the stale consistent snapshot of this transaction.
            var existing = mapper.findByNaturalKeyForUpdate(organizationId, actorMemberId,
                    operation, fingerprint);
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

    @Override
    public void finalize(long reservationId, int responseStatus, String responseBody) {
        if (mapper.finalize(reservationId, responseStatus, responseBody) != 1) {
            throw new IllegalStateException("Idempotency finalization must update exactly one row");
        }
    }
}
