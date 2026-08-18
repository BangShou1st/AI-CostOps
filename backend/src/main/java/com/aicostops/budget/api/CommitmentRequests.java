package com.aicostops.budget.api;

import com.aicostops.budget.application.BudgetCommitmentCommands.ApproveCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.CancelCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RejectCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.ReleaseCommitmentCommand;
import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.budget.domain.BudgetDecimal;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * Commitment HTTP request bodies. Money is a plain decimal string with at
 * most 8 fractional digits; ids are JSON strings; expectedVersion is the
 * optimistic-lock counter of the commitment row (project convention).
 */
public final class CommitmentRequests {

    private CommitmentRequests() {
    }

    public record CreateCommitmentRequest(
            @NotBlank String requestedAmount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    public record ApproveCommitmentRequest(@NotNull Long expectedVersion) {
    }

    public record RejectCommitmentRequest(
            @NotNull Long expectedVersion,
            @Size(max = 2000) String comment) {
    }

    public record CancelCommitmentRequest(@NotNull Long expectedVersion) {
    }

    public record ReleaseCommitmentRequest(@NotNull Long expectedVersion) {
    }

    // -- parsing (string money -> exact scale-8 BigDecimal) --------------------

    public static RequestCommitmentCommand parseCreate(CreateCommitmentRequest request,
            long budgetId) {
        return new RequestCommitmentCommand(budgetId,
                parseMoney(request.requestedAmount(), "requestedAmount"), request.currency());
    }

    public static ApproveCommitmentCommand parseApprove(ApproveCommitmentRequest request) {
        return new ApproveCommitmentCommand(request.expectedVersion());
    }

    public static RejectCommitmentCommand parseReject(RejectCommitmentRequest request) {
        return new RejectCommitmentCommand(request.expectedVersion(), request.comment());
    }

    public static CancelCommitmentCommand parseCancel(CancelCommitmentRequest request) {
        return new CancelCommitmentCommand(request.expectedVersion());
    }

    public static ReleaseCommitmentCommand parseRelease(ReleaseCommitmentRequest request) {
        return new ReleaseCommitmentCommand(request.expectedVersion());
    }

    private static long parseId(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.REQUEST_MALFORMED,
                    "Request is malformed", field + " must be a numeric id string.");
        }
    }

    private static BigDecimal parseMoney(String value, String field) {
        try {
            return BudgetDecimal.money(new BigDecimal(value));
        } catch (IllegalArgumentException malformed) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.REQUEST_MALFORMED,
                    "Request is malformed",
                    field + " must be an exact decimal with at most 8 fractional digits.");
        }
    }
}
