package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.ReconciliationCaseService;
import com.aicostops.reconciliation.application.ReconciliationCaseService.ResolveCaseCommand;
import com.aicostops.reconciliation.application.ReconciliationQueryService;
import com.aicostops.reconciliation.application.ReconciliationRunService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public final class ReconciliationController {

    private final ReconciliationRunService runs;
    private final ReconciliationQueryService queries;
    private final ReconciliationCaseService cases;
    private final ObjectMapper objectMapper;

    public ReconciliationController(
            ReconciliationRunService runs,
            ReconciliationQueryService queries,
            ReconciliationCaseService cases,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.queries = queries;
        this.cases = cases;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/v1/reconciliation-runs")
    public ReconciliationResponses.RunResponse run(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ReconciliationRequests.RunRequest request) {
        var periodId = parseId(request == null ? null : request.billingPeriodId(), "billingPeriodId");
        return ReconciliationResponses.RunResponse.from(runs.run(user, periodId), objectMapper);
    }

    @GetMapping("/api/v1/reconciliation-runs")
    public PageResponse<ReconciliationResponses.RunResponse> listRuns(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam String billingPeriodId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.listRuns(user, parseId(billingPeriodId, "billingPeriodId"), page, size);
        return new PageResponse<>(
                result.items().stream()
                        .map(run -> ReconciliationResponses.RunResponse.from(run, objectMapper))
                        .toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/api/v1/reconciliation-runs/{runId}")
    public ReconciliationResponses.RunResponse getRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long runId) {
        return ReconciliationResponses.RunResponse.from(queries.getRun(user, runId), objectMapper);
    }

    @GetMapping("/api/v1/reconciliation-cases")
    public PageResponse<ReconciliationResponses.CaseResponse> listCases(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam String runId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.listCases(user, parseId(runId, "runId"), status, page, size);
        return new PageResponse<>(
                result.items().stream().map(ReconciliationResponses.CaseResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/api/v1/reconciliation-cases/{caseId}")
    public ReconciliationResponses.CaseResponse getCase(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId) {
        return ReconciliationResponses.CaseResponse.from(queries.getCase(user, caseId));
    }

    @PostMapping("/api/v1/reconciliation-cases/{caseId}/investigate")
    public ReconciliationResponses.CaseResponse investigate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId) {
        return ReconciliationResponses.CaseResponse.from(cases.investigate(user, caseId));
    }

    @PostMapping("/api/v1/reconciliation-cases/{caseId}/return-open")
    public ReconciliationResponses.CaseResponse returnOpen(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId) {
        return ReconciliationResponses.CaseResponse.from(cases.returnOpen(user, caseId));
    }

    @PostMapping("/api/v1/reconciliation-cases/{caseId}/resolve")
    public ReconciliationResponses.CaseResponse resolve(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId,
            @RequestBody ReconciliationRequests.ResolveCaseRequest request) {
        var command = new ResolveCaseCommand(
                request == null ? null : request.reasonCode(),
                request == null ? null : request.resolutionNote());
        return ReconciliationResponses.CaseResponse.from(cases.resolve(user, caseId, command));
    }

    private static long parseId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalidId(field);
        }
        try {
            var value = Long.parseLong(raw);
            if (value <= 0) {
                throw invalidId(field);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw invalidId(field);
        }
    }

    private static DomainException invalidId(String field) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid identifier", field + " must be a positive decimal identifier.");
    }
}
