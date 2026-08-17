package com.aicostops.expense.api;

import static com.aicostops.expense.api.ExpenseRequests.parseCreate;
import static com.aicostops.expense.api.ExpenseRequests.parseEdit;

import com.aicostops.expense.api.ExpenseRequests.CreateExpenseRequest;
import com.aicostops.expense.api.ExpenseRequests.EditExpenseRequest;
import com.aicostops.expense.api.ExpenseResponses.ExpenseResponse;
import com.aicostops.expense.api.ExpenseResponses.ExpenseSummaryResponse;
import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee expense claim API: create DRAFT (idempotent), list my expenses,
 * owner-scoped detail, and optimistic-version body edit. Authorization and the
 * OWN guard run in the services before any resource lookup.
 */
@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseController {

    private final ExpenseClaimCommandService commands;
    private final ExpenseQueryService queries;

    public ExpenseController(
            ExpenseClaimCommandService commands,
            ExpenseQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    public ExpenseResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateExpenseRequest request) {
        var detail = commands.create(authenticatedUser, parseCreate(request), idempotencyKey);
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }

    @GetMapping
    public PageResponse<ExpenseSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var total = queries.countMine(authenticatedUser);
        var items = queries.listMine(authenticatedUser, page, size).stream()
                .map(ExpenseSummaryResponse::from)
                .toList();
        var totalPages = total == 0 ? 0 : ((total - 1) / Math.max(size, 1)) + 1;
        return new PageResponse<>(items, Math.max(page, 0), Math.max(size, 1), total, totalPages);
    }

    @GetMapping("/{expenseId}")
    public ExpenseResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId) {
        var detail = queries.getOwned(authenticatedUser, expenseId);
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }

    @PutMapping("/{expenseId}")
    public ExpenseResponse edit(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId,
            @Valid @RequestBody EditExpenseRequest request) {
        var detail = commands.edit(authenticatedUser, expenseId, parseEdit(request));
        return ExpenseResponse.from(detail, detail.claimantMemberId());
    }
}
