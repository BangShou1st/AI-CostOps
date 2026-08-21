package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.PeriodCloseQueryService;
import com.aicostops.reconciliation.application.PeriodCloseService;
import com.aicostops.reconciliation.application.PeriodCloseService.ReopenPeriodCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public final class PeriodCloseController {

    private final PeriodCloseQueryService queries;
    private final PeriodCloseService commands;
    private final ObjectMapper objectMapper;

    public PeriodCloseController(
            PeriodCloseQueryService queries,
            PeriodCloseService commands,
            ObjectMapper objectMapper) {
        this.queries = queries;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/billing-periods/{periodId}/close-readiness")
    public PeriodCloseResponses.ReadinessResponse readiness(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long periodId) {
        return PeriodCloseResponses.ReadinessResponse.from(queries.preview(user, periodId));
    }

    @GetMapping("/api/v1/billing-periods/{periodId}/close-runs")
    public PageResponse<PeriodCloseResponses.CloseRunResponse> listRuns(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long periodId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.listRuns(user, periodId, page, size);
        return new PageResponse<>(
                result.items().stream()
                        .map(view -> PeriodCloseResponses.CloseRunResponse.from(view, objectMapper))
                        .toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/api/v1/billing-periods/{periodId}/close-runs/{runId}")
    public PeriodCloseResponses.CloseRunResponse getRun(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long periodId,
            @PathVariable long runId) {
        return PeriodCloseResponses.CloseRunResponse.from(
                queries.getRun(user, periodId, runId), objectMapper);
    }

    @PostMapping("/api/v1/billing-periods/{periodId}/close")
    public PeriodCloseResponses.CloseRunResponse close(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long periodId) {
        return PeriodCloseResponses.CloseRunResponse.from(
                commands.close(user, periodId), objectMapper);
    }

    @PostMapping("/api/v1/billing-periods/{periodId}/reopen")
    public PeriodCloseResponses.CloseRunResponse reopen(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long periodId,
            @RequestBody PeriodCloseRequests.ReopenPeriodRequest request) {
        return PeriodCloseResponses.CloseRunResponse.from(
                commands.reopen(user, periodId, new ReopenPeriodCommand(
                        request == null ? null : request.reasonCode(),
                        request == null ? null : request.reasonNote())),
                objectMapper);
    }
}
