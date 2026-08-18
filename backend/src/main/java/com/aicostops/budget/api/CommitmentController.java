package com.aicostops.budget.api;

import static com.aicostops.budget.api.CommitmentRequests.parseApprove;
import static com.aicostops.budget.api.CommitmentRequests.parseCancel;
import static com.aicostops.budget.api.CommitmentRequests.parseCreate;
import static com.aicostops.budget.api.CommitmentRequests.parseReject;
import static com.aicostops.budget.api.CommitmentRequests.parseRelease;

import com.aicostops.budget.api.CommitmentRequests.ApproveCommitmentRequest;
import com.aicostops.budget.api.CommitmentRequests.CancelCommitmentRequest;
import com.aicostops.budget.api.CommitmentRequests.CreateCommitmentRequest;
import com.aicostops.budget.api.CommitmentRequests.RejectCommitmentRequest;
import com.aicostops.budget.api.CommitmentRequests.ReleaseCommitmentRequest;
import com.aicostops.budget.api.CommitmentResponses.CommitmentResponse;
import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
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
 * Commitment API: request (Idempotency-Key), org-scoped read with grant
 * visibility, and the approve/reject/cancel/release mutations
 * (Idempotency-Key). The consume primitive is intentionally NOT exposed as
 * an HTTP endpoint — it is composed inside the future ledger posting
 * transaction (AIC-048).
 */
@RestController
@RequestMapping("/api/v1")
public class CommitmentController {

    private final BudgetCommitmentCommandService commands;
    private final BudgetCommitmentQueryService queries;

    public CommitmentController(BudgetCommitmentCommandService commands,
            BudgetCommitmentQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping("/budgets/{budgetId}/commitments")
    public CommitmentResponse request(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long budgetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCommitmentRequest request) {
        return CommitmentResponse.from(commands.request(authenticatedUser,
                parseCreate(request, budgetId), idempotencyKey));
    }

    @GetMapping("/commitments")
    public PageResponse<CommitmentResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long budgetId,
            @RequestParam(required = false) String status) {
        var result = queries.list(authenticatedUser,
                PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 200), 1)),
                budgetId, status);
        return new PageResponse<>(
                result.items().stream().map(CommitmentResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/commitments/{commitmentId}")
    public CommitmentResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long commitmentId) {
        return CommitmentResponse.from(queries.get(authenticatedUser, commitmentId));
    }

    @PostMapping("/commitments/{commitmentId}/approve")
    public CommitmentResponse approve(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long commitmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ApproveCommitmentRequest request) {
        return CommitmentResponse.from(commands.approve(authenticatedUser, commitmentId,
                parseApprove(request), idempotencyKey));
    }

    @PostMapping("/commitments/{commitmentId}/reject")
    public CommitmentResponse reject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long commitmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RejectCommitmentRequest request) {
        return CommitmentResponse.from(commands.reject(authenticatedUser, commitmentId,
                parseReject(request), idempotencyKey));
    }

    @PostMapping("/commitments/{commitmentId}/cancel")
    public CommitmentResponse cancel(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long commitmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CancelCommitmentRequest request) {
        return CommitmentResponse.from(commands.cancel(authenticatedUser, commitmentId,
                parseCancel(request), idempotencyKey));
    }

    @PostMapping("/commitments/{commitmentId}/release")
    public CommitmentResponse release(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long commitmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReleaseCommitmentRequest request) {
        return CommitmentResponse.from(commands.release(authenticatedUser, commitmentId,
                parseRelease(request), idempotencyKey));
    }
}
