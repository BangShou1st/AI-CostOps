package com.aicostops.budget.api;

import static com.aicostops.budget.api.BudgetRequests.parseCreate;
import static com.aicostops.budget.api.BudgetRequests.parseUpdate;

import com.aicostops.budget.api.BudgetRequests.CreateBudgetRequest;
import com.aicostops.budget.api.BudgetRequests.UpdateBudgetRequest;
import com.aicostops.budget.api.BudgetResponses.BudgetResponse;
import com.aicostops.budget.application.BudgetCommandService;
import com.aicostops.budget.application.BudgetQueryService;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget management API: create (natural identity, no Idempotency-Key
 * required), org-scoped paged list with grant-scoped visibility, detail with
 * privacy 404s, and manageable-field update with an optimistic version CAS.
 * Authorization runs in the services before any resource lookup.
 */
@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetCommandService commands;
    private final BudgetQueryService queries;

    public BudgetController(BudgetCommandService commands, BudgetQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateBudgetRequest request) {
        return BudgetResponse.from(commands.create(authenticatedUser, parseCreate(request)));
    }

    @GetMapping
    public PageResponse<BudgetResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long billingPeriodId,
            @RequestParam(required = false) ScopeType scopeType,
            @RequestParam(required = false) Long scopeId) {
        var result = queries.list(authenticatedUser,
                PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 200), 1)),
                billingPeriodId, scopeType, scopeId);
        return new PageResponse<>(
                result.items().stream().map(BudgetResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/{budgetId}")
    public BudgetResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long budgetId) {
        return BudgetResponse.from(queries.get(authenticatedUser, budgetId));
    }

    @PutMapping("/{budgetId}")
    public BudgetResponse update(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long budgetId,
            @Valid @RequestBody UpdateBudgetRequest request) {
        return BudgetResponse.from(commands.update(authenticatedUser, budgetId,
                parseUpdate(request)));
    }
}