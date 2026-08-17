package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.RuleDefinitionCommand;
import com.aicostops.allocation.infrastructure.AllocationIdempotencyMapper;
import com.aicostops.allocation.infrastructure.AllocationIdempotencyMapper.IdempotencyRow;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Reservation/replay helper for idempotent allocation commands, bound to the
 * existing generic {@code api_idempotency} table.
 *
 * <p>The raw Idempotency-Key header is never stored: the natural key column
 * keeps {@code lowercaseHex(SHA-256(exact UTF-8 key))}. The request hash covers
 * operation + organization/actor context + the command's resource identity and
 * canonical body. A provisional {@code response_status=0} row exists only
 * uncommitted inside the command transaction; a committed provisional row is
 * an invariant violation.
 */
@Service
public class AllocationIdempotency {

    public static final String OPERATION_MANUAL_DRAFT = "ALLOCATION_MANUAL_DRAFT";
    public static final String OPERATION_CONFIRM = "ALLOCATION_CONFIRM";
    public static final String OPERATION_PROPOSAL = "ALLOCATION_PROPOSAL";
    public static final String OPERATION_RULE_VERSION = "ALLOCATION_RULE_VERSION";
    public static final String OPERATION_RULE_ARCHIVE = "ALLOCATION_RULE_ARCHIVE";

    private static final int MAX_KEY_CHARACTERS = 200;

    private final AllocationIdempotencyMapper mapper;
    private final Clock clock;

    public AllocationIdempotency(AllocationIdempotencyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Outcome of a reservation attempt inside the caller's transaction. */
    public record IdempotencyDecision(long id, boolean replay, int responseStatus, String responseBody) {
    }

    /** HTTP-bound validation; must run before any database mutation. */
    public static void validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_CHARACTERS) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Idempotency-Key is invalid",
                    "Idempotency-Key must be a nonblank value of at most 200 characters.");
        }
    }

    public String manualDraftRequestHash(long organizationId, long actorMemberId, long chargeFactId,
            List<AllocationLineCommand> lines) {
        var canonical = new StringBuilder("operation=").append(OPERATION_MANUAL_DRAFT)
                .append("\norgId=").append(organizationId)
                .append("\nactorMemberId=").append(actorMemberId)
                .append("\nchargeFactId=").append(chargeFactId);
        for (var line : lines) {
            canonical.append("\nline=").append(line.allocatedAmount().toPlainString())
                    .append(";").append(line.currency())
                    .append(";").append(nullable(line.projectId()))
                    .append(";").append(nullable(line.costCenterId()))
                    .append(";").append(nullable(line.teamId()));
        }
        return sha256Hex(canonical.toString());
    }

    public String confirmRequestHash(long organizationId, long actorMemberId, long decisionId) {
        var canonical = "operation=" + OPERATION_CONFIRM
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\ndecisionId=" + decisionId
                + "\nbody={}";
        return sha256Hex(canonical);
    }

    public String proposalRequestHash(long organizationId, long actorMemberId, long chargeFactId) {
        var canonical = "operation=" + OPERATION_PROPOSAL
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nchargeFactId=" + chargeFactId
                + "\nbody={}";
        return sha256Hex(canonical);
    }

    public String ruleVersionRequestHash(long organizationId, long actorMemberId, String ruleKey,
            RuleDefinitionCommand definition) {
        var canonical = new StringBuilder("operation=").append(OPERATION_RULE_VERSION)
                .append("\norgId=").append(organizationId)
                .append("\nactorMemberId=").append(actorMemberId)
                .append("\nruleKey=").append(ruleKey)
                .append("\ndefinition=")
                .append(definition.name())
                .append(";").append(definition.providerCode())
                .append(";").append(nullable(definition.providerAccountId()))
                .append(";").append(definition.matchHintType().name())
                .append(";").append(definition.matchValue())
                .append(";").append(definition.priority())
                .append(";").append(nullable(definition.targetProjectId()))
                .append(";").append(nullable(definition.targetCostCenterId()))
                .append(";").append(nullable(definition.targetTeamId()))
                .append(";").append(definition.effectiveFrom())
                .append(";").append(nullable(definition.effectiveTo()));
        return sha256Hex(canonical.toString());
    }

    public String ruleArchiveRequestHash(long organizationId, long actorMemberId, long ruleId) {
        var canonical = "operation=" + OPERATION_RULE_ARCHIVE
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nruleId=" + ruleId
                + "\nbody={}";
        return sha256Hex(canonical);
    }

    /**
     * Reserves the key or replays the stored completed response. Must be called
     * inside the caller's transaction, before any state mutation.
     */
    public IdempotencyDecision reserve(
            long orgId, long actorMemberId, String operation, String rawKey, String requestHash) {
        var fingerprint = keyFingerprint(rawKey);
        try {
            mapper.insertProvisional(orgId, actorMemberId, operation, fingerprint, requestHash,
                    clock.instant());
            return new IdempotencyDecision(mapper.lastInsertId(), false, 0, null);
        } catch (DuplicateKeyException concurrent) {
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

    private static String nullable(Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    private static String nullable(Instant value) {
        return value == null ? "null" : value.toString();
    }

    /** 64-char lowercase hex SHA-256 fingerprint of the exact UTF-8 key value. */
    public static String keyFingerprint(String rawKey) {
        return sha256Hex(rawKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
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
