package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.application.LedgerCorrectionIdempotencyStore;
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

/** Reservation/replay implementation backed by the shared idempotency table. */
@Component
public class MyBatisLedgerCorrectionIdempotencyStore implements LedgerCorrectionIdempotencyStore {

    private final LedgerCorrectionIdempotencyMapper mapper;
    private final Clock clock;

    public MyBatisLedgerCorrectionIdempotencyStore(LedgerCorrectionIdempotencyMapper mapper,
            Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
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

    private static String keyFingerprint(String rawKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
