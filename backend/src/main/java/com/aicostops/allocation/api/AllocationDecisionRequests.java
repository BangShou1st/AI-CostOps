package com.aicostops.allocation.api;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand;
import com.aicostops.attribution.domain.AllocationDecimal;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Allocation command request bodies plus HTTP-bound shape validation:
 * exact 8-decimal money strings, 3-letter currency, exactly one target.
 */
public final class AllocationDecisionRequests {

    private static final Pattern EXACT_MONEY = Pattern.compile("^-?[0-9]+\\.[0-9]{8}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Za-z]{3}$");

    private AllocationDecisionRequests() {
    }

    public record AllocationLineRequest(
            String allocatedAmount,
            String currency,
            ApiId projectId,
            ApiId costCenterId,
            ApiId teamId) {
    }

    public record ManualDraftRequest(@NotEmpty List<AllocationLineRequest> lines) {
    }

    public record ReplaceLinesRequest(@NotEmpty List<AllocationLineRequest> lines) {
    }

    public static ManualDraftCommand parse(ManualDraftRequest request) {
        return new ManualDraftCommand(parseLines(request.lines()));
    }

    public static List<AllocationLineCommand> parseLines(List<AllocationLineRequest> input) {
        if (input == null || input.isEmpty()) {
            throw validation("At least one allocation line is required.");
        }
        return input.stream().map(AllocationDecisionRequests::parseLine).toList();
    }

    private static AllocationLineCommand parseLine(AllocationLineRequest line) {
        if (line == null) {
            throw validation("Each allocation line must be a non-null object.");
        }
        var amount = line.allocatedAmount();
        if (amount == null || !EXACT_MONEY.matcher(amount).matches()) {
            throw validation(
                    "allocatedAmount must be a decimal string with exactly 8 fractional digits.");
        }
        BigDecimal canonical;
        try {
            // Syntax is exact-8dp already; the money guard then rejects values
            // that are not exactly representable in DECIMAL(20,8) at parse
            // time instead of failing deep inside the persistence layer.
            canonical = AllocationDecimal.money(new BigDecimal(amount));
        } catch (IllegalArgumentException invalid) {
            throw validation(
                    "allocatedAmount must be a decimal string with exactly 8 fractional digits "
                    + "that fits DECIMAL(20,8).");
        }
        var currency = line.currency();
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw validation("currency must be a 3-letter code.");
        }
        var projectId = line.projectId() == null ? null : line.projectId().value();
        var costCenterId = line.costCenterId() == null ? null : line.costCenterId().value();
        var teamId = line.teamId() == null ? null : line.teamId().value();
        var targetCount = (projectId == null ? 0 : 1)
                + (costCenterId == null ? 0 : 1)
                + (teamId == null ? 0 : 1);
        if (targetCount != 1) {
            throw validation(
                    "Exactly one target is required: projectId, costCenterId, or teamId.");
        }
        return new AllocationLineCommand(
                canonical, currency.toUpperCase(Locale.ROOT), projectId, costCenterId, teamId);
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid allocation request", detail);
    }
}
