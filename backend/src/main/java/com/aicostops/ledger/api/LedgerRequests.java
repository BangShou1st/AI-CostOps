package com.aicostops.ledger.api;

import com.aicostops.ledger.application.LedgerPostingCommands;
import com.aicostops.ledger.application.LedgerPostingCommands.CommitmentLink;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
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
