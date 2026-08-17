package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseIdempotencyStore.IdempotencyDecision;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Expense idempotency helper: key validation, canonical request hashes, and
 * delegation to {@link ExpenseIdempotencyStore}. The request hash covers the
 * operation + organization/actor + canonical body (including expectedVersion
 * and comment), so a replayed key with a different body is a 409 conflict.
 */
@Component
public final class ExpenseIdempotency {

    public static final String OPERATION_CREATE = "EXPENSE_CREATE";
    public static final String OPERATION_SUBMIT = "EXPENSE_SUBMIT";
    public static final String OPERATION_RESUBMIT = "EXPENSE_RESUBMIT";
    public static final String OPERATION_CANCEL = "EXPENSE_CANCEL";
    public static final String OPERATION_REQUEST_INFO = "EXPENSE_REQUEST_INFO";
    public static final String OPERATION_APPROVE = "EXPENSE_APPROVE";
    public static final String OPERATION_REJECT = "EXPENSE_REJECT";

    private static final int MAX_KEY_CHARACTERS = 200;

    private final ExpenseIdempotencyStore store;

    public ExpenseIdempotency(ExpenseIdempotencyStore store) {
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

    public String createRequestHash(long organizationId, long actorMemberId,
            LocalDate expenseDate, BigDecimal amount, String currency) {
        var canonical = "operation=" + OPERATION_CREATE
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseDate=" + expenseDate
                + "\namount=" + amount.toPlainString()
                + "\ncurrency=" + currency;
        return sha256Hex(canonical);
    }

    public String submitRequestHash(long organizationId, long actorMemberId, long expenseId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_SUBMIT
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseId=" + expenseId
                + "\nexpectedVersion=" + expectedVersion;
        return sha256Hex(canonical);
    }

    public String resubmitRequestHash(long organizationId, long actorMemberId, long expenseId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_RESUBMIT
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseId=" + expenseId
                + "\nexpectedVersion=" + expectedVersion;
        return sha256Hex(canonical);
    }

    public String cancelRequestHash(long organizationId, long actorMemberId, long expenseId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_CANCEL
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseId=" + expenseId
                + "\nexpectedVersion=" + expectedVersion;
        return sha256Hex(canonical);
    }

    public String requestInfoRequestHash(long organizationId, long actorMemberId, long expenseId,
            long expectedVersion, String comment) {
        var canonical = "operation=" + OPERATION_REQUEST_INFO
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseId=" + expenseId
                + "\nexpectedVersion=" + expectedVersion
                + "\ncomment=" + comment;
        return sha256Hex(canonical);
    }

    public String approveRequestHash(long organizationId, long actorMemberId, long expenseId,
            long expectedVersion) {
        var canonical = "operation=" + OPERATION_APPROVE
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseId=" + expenseId
                + "\nexpectedVersion=" + expectedVersion;
        return sha256Hex(canonical);
    }

    public String rejectRequestHash(long organizationId, long actorMemberId, long expenseId,
            long expectedVersion, String comment) {
        var canonical = "operation=" + OPERATION_REJECT
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nexpenseId=" + expenseId
                + "\nexpectedVersion=" + expectedVersion
                + "\ncomment=" + comment;
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
