package com.aicostops.ledger.application;

import com.aicostops.ledger.application.LedgerCorrectionIdempotencyStore.IdempotencyDecision;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Canonical request hashing and reservation helper for Ledger corrections. */
@Component
public final class LedgerCorrectionIdempotency {

    public static final String OPERATION = "LEDGER_CORRECTION";
    private static final int MAX_KEY_CHARACTERS = 200;

    private final LedgerCorrectionIdempotencyStore store;

    public LedgerCorrectionIdempotency(LedgerCorrectionIdempotencyStore store) {
        this.store = store;
    }

    public static void validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_CHARACTERS) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Idempotency-Key is invalid",
                    "Idempotency-Key must be a nonblank value of at most 200 characters.");
        }
    }

    public String requestHash(long organizationId, long actorMemberId, CorrectionCommand command) {
        var replacement = command.replacement();
        var canonical = "operation=" + OPERATION
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ntargetEntryId=" + command.targetEntryId()
                + "\ncorrectionPeriodId=" + command.correctionPeriodId()
                + "\nmode=" + command.mode()
                + "\nreasonCode=" + command.reasonCode()
                + "\nreasonText=" + value(command.reasonText())
                + "\nreplacementAmount=" + (replacement == null ? "" : decimal(replacement.amount()))
                + "\nreplacementCurrency=" + (replacement == null ? "" : value(replacement.currency()))
                + "\nreplacementProjectId=" + id(replacement == null ? null : replacement.projectId())
                + "\nreplacementCostCenterId=" + id(replacement == null ? null : replacement.costCenterId())
                + "\nreplacementTeamId=" + id(replacement == null ? null : replacement.teamId());
        return sha256Hex(canonical);
    }

    public IdempotencyDecision reserve(long organizationId, long actorMemberId, String rawKey,
            String requestHash) {
        return store.reserve(organizationId, actorMemberId, OPERATION, rawKey, requestHash);
    }

    public void finalize(long reservationId, int responseStatus, String responseBody) {
        store.finalize(reservationId, responseStatus, responseBody);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String id(Long value) {
        return value == null ? "" : Long.toString(value);
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
