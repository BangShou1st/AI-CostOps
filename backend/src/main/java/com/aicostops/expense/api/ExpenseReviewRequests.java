package com.aicostops.expense.api;

import com.aicostops.expense.application.ExpenseCommands.ApproveExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RejectExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.RequestInfoCommand;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;

/**
 * Finance review request bodies: every mutation carries the optimistic
 * expectedVersion; comment is required for request-info and reject, optional
 * elsewhere.
 */
public final class ExpenseReviewRequests {

    private ExpenseReviewRequests() {
    }

    public record RequestInfoRequest(Long expectedVersion, String comment) {
    }

    public record ApproveRequest(Long expectedVersion) {
    }

    public record RejectRequest(Long expectedVersion, String comment) {
    }

    public static RequestInfoCommand parse(RequestInfoRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        if (request.comment() == null || request.comment().isBlank()) {
            throw validation("comment is required for request-info.");
        }
        if (request.comment().length() > 2000) {
            throw validation("comment must be at most 2000 characters.");
        }
        return new RequestInfoCommand(request.expectedVersion(), request.comment().strip());
    }

    public static ApproveExpenseCommand parse(ApproveRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        return new ApproveExpenseCommand(request.expectedVersion());
    }

    public static RejectExpenseCommand parse(RejectRequest request) {
        if (request == null) {
            throw validation("A request body is required.");
        }
        if (request.expectedVersion() == null) {
            throw validation("expectedVersion is required.");
        }
        if (request.comment() == null || request.comment().isBlank()) {
            throw validation("comment is required for reject.");
        }
        if (request.comment().length() > 2000) {
            throw validation("comment must be at most 2000 characters.");
        }
        return new RejectExpenseCommand(request.expectedVersion(), request.comment().strip());
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid expense review request", detail);
    }
}