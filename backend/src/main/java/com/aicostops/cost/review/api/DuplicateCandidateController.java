package com.aicostops.cost.review.api;

import com.aicostops.cost.review.api.DuplicateCandidateResponses.DuplicateCandidateResponse;
import com.aicostops.cost.review.api.DuplicateCandidateResponses.DuplicateExcludeRequest;
import com.aicostops.cost.review.api.DuplicateCandidateResponses.DuplicateScanResponse;
import com.aicostops.cost.review.application.DuplicateReviewCommandService;
import com.aicostops.cost.review.application.DuplicateReviewQueryService;
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

@RestController
@RequestMapping("/api/v1/duplicate-candidates")
public class DuplicateCandidateController {

    private final DuplicateReviewQueryService queries;
    private final DuplicateReviewCommandService commands;

    public DuplicateCandidateController(DuplicateReviewQueryService queries,
            DuplicateReviewCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @PostMapping("/scan")
    public DuplicateScanResponse scan(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return DuplicateScanResponse.from(commands.scan(authenticatedUser));
    }

    @GetMapping
    public PageResponse<DuplicateCandidateResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String candidateType) {
        var result = queries.list(authenticatedUser, page, size, status, candidateType);
        return new PageResponse<>(
                result.items().stream().map(DuplicateCandidateResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/{candidateId}")
    public DuplicateCandidateResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long candidateId) {
        return DuplicateCandidateResponse.from(queries.get(authenticatedUser, candidateId));
    }

    @PostMapping("/{candidateId}/keep")
    public DuplicateCandidateResponse keep(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long candidateId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return DuplicateCandidateResponse.from(
                commands.keep(authenticatedUser, candidateId, idempotencyKey));
    }

    @PostMapping("/{candidateId}/exclude")
    public DuplicateCandidateResponse exclude(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long candidateId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DuplicateExcludeRequest request) {
        return DuplicateCandidateResponse.from(
                commands.exclude(authenticatedUser, candidateId, idempotencyKey,
                        request.excludedChargeFactId().value()));
    }
}
