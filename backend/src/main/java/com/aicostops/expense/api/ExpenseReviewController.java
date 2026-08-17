package com.aicostops.expense.api;

import static com.aicostops.expense.api.ExpenseReviewRequests.parse;

import com.aicostops.expense.api.ExpenseResponses.ExpenseResponse;
import com.aicostops.expense.api.ExpenseResponses.ExpenseSummaryResponse;
import com.aicostops.expense.api.ExpenseReviewRequests.ApproveRequest;
import com.aicostops.expense.api.ExpenseReviewRequests.RejectRequest;
import com.aicostops.expense.api.ExpenseReviewRequests.RequestInfoRequest;
import com.aicostops.expense.application.ExpenseReviewCommandService;
import com.aicostops.expense.application.ExpenseReviewQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finance review API: the org review queue, the reviewer detail, and the
 * idempotent request-info / approve / reject commands. EXPENSE_REVIEW is
 * enforced by the services (same org, no owner comparison).
 */
@RestController
@RequestMapping("/api/v1")
public class ExpenseReviewController {

    private final ExpenseReviewCommandService commands;
    private final ExpenseReviewQueryService queries;

    public ExpenseReviewController(
            ExpenseReviewCommandService commands,
            ExpenseReviewQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/expense-reviews")
    public PageResponse<ExpenseSummaryResponse> listQueue(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var total = queries.countQueue(authenticatedUser, status);
        var items = queries.listQueue(authenticatedUser, status, page, size).stream()
                .map(ExpenseSummaryResponse::from)
                .toList();
        var totalPages = total == 0 ? 0 : ((total - 1) / Math.max(size, 1)) + 1;
        return new PageResponse<>(items, Math.max(page, 0), Math.max(size, 1), total, totalPages);
    }

    @GetMapping("/expense-reviews/{expenseId}")
    public ExpenseResponse getForReview(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId) {
        var detail = queries.getForReview(authenticatedUser, expenseId);
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }

    @PostMapping("/expenses/{expenseId}/request-info")
    public ExpenseResponse requestInfo(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RequestInfoRequest request) {
        var detail = commands.requestInfo(authenticatedUser, expenseId,
                parse(request), idempotencyKey);
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }

    @PostMapping("/expenses/{expenseId}/approve")
    public ExpenseResponse approve(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ApproveRequest request) {
        var detail = commands.approve(authenticatedUser, expenseId,
                parse(request), idempotencyKey);
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }

    @PostMapping("/expenses/{expenseId}/reject")
    public ExpenseResponse reject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RejectRequest request) {
        var detail = commands.reject(authenticatedUser, expenseId,
                parse(request), idempotencyKey);
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }
}