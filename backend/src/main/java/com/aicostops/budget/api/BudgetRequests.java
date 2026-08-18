package com.aicostops.budget.api;

import com.aicostops.budget.application.BudgetCommands;
import com.aicostops.budget.domain.BudgetDecimal;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Budget request bodies plus HTTP-bound shape validation: exact 8-decimal
 * money strings, string ids, 3-letter currency, and a valid scope enum.
 * Negative totals are rejected here (total_amount is never negative).
 */
public final class BudgetRequests {

    private static final Pattern EXACT_MONEY = Pattern.compile("^-?[0-9]+\\.[0-9]{8}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Za-z]{3}$");

    private BudgetRequests() {
    }

    public record CreateBudgetRequest(
            String billingPeriodId,
            String scopeType,
            String scopeId,
            String currency,
            String totalAmount) {
    }

    public record UpdateBudgetRequest(
            String totalAmount,
            Integer expectedVersion) {
    }

    public static BudgetCommands.CreateBudgetCommand parseCreate(CreateBudgetRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        return new BudgetCommands.CreateBudgetCommand(
                parseId(request.billingPeriodId(), "billingPeriodId"),
                parseScopeType(request.scopeType()),
                parseId(request.scopeId(), "scopeId"),
                parseCurrency(request.currency()),
                parseTotal(request.totalAmount()));
    }

    public static BudgetCommands.UpdateBudgetCommand parseUpdate(UpdateBudgetRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        return new BudgetCommands.UpdateBudgetCommand(
                parseTotal(request.totalAmount()), request.expectedVersion());
    }

    private static long parseId(String value, String field) {
        if (value == null || !value.matches("^[0-9]+$")) {
            throw validation(field + " must be a positive integer encoded as a string.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooLarge) {
            throw validation(field + " is out of range.");
        }
    }

    private static ScopeType parseScopeType(String value) {
        if (value == null) {
            throw validation("scopeType is required (ORG, PROJECT, TEAM, or COST_CENTER).");
        }
        try {
            return ScopeType.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw validation("scopeType must be ORG, PROJECT, TEAM, or COST_CENTER.");
        }
    }

    private static String parseCurrency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw validation("currency must be a 3-letter code.");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal parseTotal(String value) {
        if (value == null || !EXACT_MONEY.matcher(value).matches()) {
            throw validation(
                    "totalAmount must be a decimal string with exactly 8 fractional digits.");
        }
        BigDecimal amount;
        try {
            amount = BudgetDecimal.money(new BigDecimal(value));
        } catch (IllegalArgumentException invalid) {
            throw validation(
                    "totalAmount must be a decimal string with exactly 8 fractional digits "
                            + "that fits DECIMAL(20,8).");
        }
        if (amount.signum() < 0) {
            throw validation("totalAmount must not be negative.");
        }
        return amount;
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid budget request", detail);
    }
}