package com.aicostops.expense.api;

import com.aicostops.expense.application.ExpenseCommands;
import com.aicostops.expense.domain.ExpenseMoneyPolicy;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Expense request bodies plus HTTP-bound shape validation: exact 8-decimal
 * money strings, ISO date, 3-letter currency.
 */
public final class ExpenseRequests {

    private static final Pattern EXACT_MONEY = Pattern.compile("^-?[0-9]+\\.[0-9]{8}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Za-z]{3}$");

    private ExpenseRequests() {
    }

    public record CreateExpenseRequest(
            String expenseDate,
            String amount,
            String currency) {
    }

    public record EditExpenseRequest(
            String expenseDate,
            String amount,
            String currency,
            Long expectedVersion) {
    }

    public record SubmitRequest(Long expectedVersion) {
    }

    public record CancelRequest(Long expectedVersion) {
    }

    public static ExpenseCommands.SubmitExpenseCommand parse(SubmitRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        return new ExpenseCommands.SubmitExpenseCommand(request.expectedVersion());
    }

    public static ExpenseCommands.CancelExpenseCommand parse(CancelRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        return new ExpenseCommands.CancelExpenseCommand(request.expectedVersion());
    }

    public static ExpenseCommands.CreateExpenseCommand parseCreate(CreateExpenseRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        return new ExpenseCommands.CreateExpenseCommand(
                parseExpenseDate(request.expenseDate()),
                parseAmount(request.amount()),
                parseCurrency(request.currency()));
    }

    public static ExpenseCommands.EditExpenseCommand parseEdit(EditExpenseRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        return new ExpenseCommands.EditExpenseCommand(
                parseExpenseDate(request.expenseDate()),
                parseAmount(request.amount()),
                parseCurrency(request.currency()),
                request.expectedVersion());
    }

    private static LocalDate parseExpenseDate(String value) {
        if (value == null) {
            throw validation("expenseDate is required (ISO-8601 date, e.g. 2026-08-17).");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw validation("expenseDate must be an ISO-8601 date (yyyy-MM-dd).");
        }
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || !EXACT_MONEY.matcher(value).matches()) {
            throw validation(
                    "amount must be a decimal string with exactly 8 fractional digits.");
        }
        try {
            return ExpenseMoneyPolicy.money(new BigDecimal(value));
        } catch (IllegalArgumentException invalid) {
            throw validation(
                    "amount must be a decimal string with exactly 8 fractional digits "
                    + "that fits DECIMAL(20,8).");
        }
    }

    private static String parseCurrency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw validation("currency must be a 3-letter code.");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid expense request", detail);
    }
}
