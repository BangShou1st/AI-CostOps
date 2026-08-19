package com.aicostops.ledger.api;

import com.aicostops.ledger.application.LedgerPostingCommands;
import com.aicostops.ledger.application.LedgerPostingCommands.CommitmentLink;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand.Replacement;
import com.aicostops.ledger.domain.CorrectionMode;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** HTTP parsing for posting commands; financial IDs and amounts stay strings. */
public final class LedgerRequests {

    private static final Pattern POSITIVE_ID = Pattern.compile("^[1-9][0-9]*$");

    private LedgerRequests() {
    }

    public record CommitmentLinkRequest(String allocationLineId, String commitmentId) {
    }

    public record PostSourceRequest(List<CommitmentLinkRequest> commitmentLinks) {
    }

    public record CorrectionRequest(
            String targetEntryId,
            String correctionPeriodId,
            String mode,
            String reasonCode,
            String reasonText,
            ReplacementRequest replacement) {
    }

    public record ReplacementRequest(
            String amount,
            String currency,
            String projectId,
            String costCenterId,
            String teamId) {
    }

    public static LedgerPostingCommands.PostSourceCommand parsePost(PostSourceRequest request) {
        var requests = request == null || request.commitmentLinks() == null
                ? List.<CommitmentLinkRequest>of() : request.commitmentLinks();
        return new LedgerPostingCommands.PostSourceCommand(requests.stream()
                .map(LedgerRequests::parseLink)
                .toList());
    }

    private static CommitmentLink parseLink(CommitmentLinkRequest request) {
        if (request == null) {
            throw validation("commitmentLinks cannot contain null items.");
        }
        return new CommitmentLink(parseId(request.allocationLineId(), "allocationLineId"),
                parseId(request.commitmentId(), "commitmentId"));
    }

    public static CorrectionCommand parseCorrection(CorrectionRequest request) {
        if (request == null) {
            throw validation("Correction request is required.");
        }
        CorrectionMode mode;
        try {
            mode = request.mode() == null ? null : CorrectionMode.valueOf(request.mode());
        } catch (IllegalArgumentException invalidMode) {
            throw validation("mode must be REVERSAL_ONLY or REPLACE.");
        }
        var replacement = request.replacement() == null ? null : parseReplacement(request.replacement());
        return new CorrectionCommand(parseId(request.targetEntryId(), "targetEntryId"),
                parseId(request.correctionPeriodId(), "correctionPeriodId"), mode,
                request.reasonCode(), request.reasonText(), replacement);
    }

    private static Replacement parseReplacement(ReplacementRequest request) {
        BigDecimal amount;
        try {
            amount = request.amount() == null ? null : new BigDecimal(request.amount());
        } catch (NumberFormatException invalidAmount) {
            throw validation("replacement.amount must be a decimal string.");
        }
        return new Replacement(amount, request.currency(), optionalId(request.projectId(), "projectId"),
                optionalId(request.costCenterId(), "costCenterId"), optionalId(request.teamId(), "teamId"));
    }

    private static Long optionalId(String value, String field) {
        if (value == null) {
            return null;
        }
        return parseId(value, "replacement." + field);
    }

    private static long parseId(String value, String field) {
        if (value == null || !POSITIVE_ID.matcher(value).matches()) {
            throw validation(field + " must be a positive integer encoded as a string.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException outOfRange) {
            throw validation(field + " is out of range.");
        }
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid posting request", detail);
    }
}
