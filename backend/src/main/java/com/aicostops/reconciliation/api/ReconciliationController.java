package com.aicostops.reconciliation.api;

import com.aicostops.iam.domain.ScopeType;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.reconciliation.application.HybridReconciliationActionService;
import com.aicostops.reconciliation.application.HybridReconciliationActionService.ChargeDispositionCommand;
import com.aicostops.reconciliation.application.HybridReconciliationActionService.CorrectionLinkCommand;
import com.aicostops.reconciliation.application.HybridReconciliationQueryService;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.CaseFullAdjustmentCommand;
import com.aicostops.reconciliation.application.ReconciliationAdjustmentService.AdjustmentLine;
import com.aicostops.reconciliation.application.ReconciliationCaseService;
import com.aicostops.reconciliation.application.ReconciliationCaseService.ResolveCaseCommand;
import com.aicostops.reconciliation.application.ReconciliationQueryService;
import com.aicostops.reconciliation.application.ReconciliationRunService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageResponse;
import java.util.List;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public final class ReconciliationController {

    private final ReconciliationRunService runs;
    private final ReconciliationQueryService queries;
    private final ReconciliationCaseService cases;
    private final HybridReconciliationQueryService hybridQueries;
    private final HybridReconciliationActionService hybridActions;
    private final ReconciliationAdjustmentService adjustments;
    private final GatewayFinancialResolutionService gatewayResolutions;
    private final ObjectMapper objectMapper;

    public ReconciliationController(
            ReconciliationRunService runs,
            ReconciliationQueryService queries,
            ReconciliationCaseService cases,
            HybridReconciliationQueryService hybridQueries,
            HybridReconciliationActionService hybridActions,
            ReconciliationAdjustmentService adjustments,
            GatewayFinancialResolutionService gatewayResolutions,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.queries = queries;
        this.cases = cases;
        this.hybridQueries = hybridQueries;
        this.hybridActions = hybridActions;
        this.adjustments = adjustments;
        this.gatewayResolutions = gatewayResolutions;
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

    @GetMapping("/api/v1/reconciliation-runs/{runId}/evidence")
    public PageResponse<ReconciliationResponses.EvidenceResponse> listRunEvidence(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = hybridQueries.listRunEvidence(user, runId, page, size);
        return new PageResponse<>(
                result.items().stream()
                        .map(ReconciliationResponses.EvidenceResponse::from)
                        .toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/api/v1/reconciliation-cases/{caseId}/evidence")
    public PageResponse<ReconciliationResponses.EvidenceResponse> listCaseEvidence(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = hybridQueries.listCaseEvidence(user, caseId, page, size);
        return new PageResponse<>(
                result.items().stream()
                        .map(ReconciliationResponses.EvidenceResponse::from)
                        .toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @PostMapping("/api/v1/reconciliation-cases/{caseId}/charge-dispositions")
    public ReconciliationResponses.ChargeDispositionResponse decideChargeDisposition(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReconciliationRequests.ChargeDispositionRequest request) {
        var command = new ChargeDispositionCommand(
                parseId(request == null ? null : request.chargeFactId(), "chargeFactId"),
                request == null ? null : request.disposition(),
                request == null ? null : request.reasonCode(),
                request == null ? null : request.reasonNote());
        var dispositionId = hybridActions.decideChargeDisposition(user, caseId, command,
                idempotencyKey);
        return ReconciliationResponses.ChargeDispositionResponse.from(dispositionId, caseId,
                command.chargeFactId(), command.disposition());
    }

    @PostMapping("/api/v1/reconciliation-cases/{caseId}/adjustments")
    public ReconciliationResponses.AdjustmentResponse postCaseAdjustment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReconciliationRequests.CaseAdjustmentRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw invalidId("lines");
        }
        List<AdjustmentLine> lines = request.lines().stream().map(line -> new AdjustmentLine(
                parseNonNegativeInt(line.lineIndex(), "lineIndex"),
                parseScopeType(line.scopeType()),
                parseId(line.scopeId(), "scopeId"),
                parseAmount(line.amount(), "amount"))).toList();
        var command = new CaseFullAdjustmentCommand(caseId,
                parseAmount(request.amount(), "amount"),
                parseId(request.adjustmentPeriodId(), "adjustmentPeriodId"),
                lines, request.reasonCode(), request.reasonNote());
        var result = adjustments.postCaseFullAdjustment(user, command, idempotencyKey);
        return ReconciliationResponses.AdjustmentResponse.from(caseId, result);
    }

    @PostMapping("/api/v1/reconciliation-runs/{runId}/gateway-resolutions")
    public ReconciliationResponses.GatewayResolutionResponse postGatewayResolution(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReconciliationRequests.GatewayResolutionRequest request) {
        var command = new GatewayResolutionCommand(
                runId,
                request == null || request.caseId() == null || request.caseId().isBlank()
                        ? null : parseId(request.caseId(), "caseId"),
                parseId(request == null ? null : request.requestId(), "requestId"),
                request == null ? null : request.resolutionType(),
                request == null || request.adjustmentAmount() == null
                        || request.adjustmentAmount().isBlank() ? null
                        : parseAmount(request.adjustmentAmount(), "adjustmentAmount"),
                request == null || request.correctionPeriodId() == null
                        || request.correctionPeriodId().isBlank() ? null
                        : parseId(request.correctionPeriodId(), "correctionPeriodId"),
                request == null || request.commitmentId() == null
                        || request.commitmentId().isBlank() ? null
                        : parseId(request.commitmentId(), "commitmentId"),
                request == null ? null : request.reasonCode(),
                request == null ? null : request.reasonNote());
        var result = gatewayResolutions.resolveGatewayFinancialWork(user, command,
                idempotencyKey);
        return ReconciliationResponses.GatewayResolutionResponse.from(result.resolutionId(),
                runId, command.caseId(), command.requestId(), command.resolutionType(),
                result.reservationOutcome(), result.adjustmentId());
    }

    @PostMapping("/api/v1/reconciliation-cases/{caseId}/link-correction")
    public ReconciliationResponses.CorrectionLinkResponse linkCorrection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long caseId,
            @RequestBody ReconciliationRequests.LinkCorrectionRequest request) {
        var correctionGroupId = parseId(request == null ? null : request.correctionGroupId(),
                "correctionGroupId");
        hybridActions.linkCorrection(user, caseId, new CorrectionLinkCommand(correctionGroupId));
        return new ReconciliationResponses.CorrectionLinkResponse(
                Long.toString(caseId), Long.toString(correctionGroupId));
    }

    private static int parseNonNegativeInt(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalidId(field);
        }
        try {
            var value = Integer.parseInt(raw.strip());
            if (value < 0) {
                throw invalidId(field);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw invalidId(field);
        }
    }

    private static java.math.BigDecimal parseAmount(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalidId(field);
        }
        try {
            return new java.math.BigDecimal(raw.strip());
        } catch (NumberFormatException invalid) {
            throw invalidId(field);
        }
    }

    private static String parseScopeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidId("scopeType");
        }
        try {
            return ScopeType.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException invalid) {
            throw invalidId("scopeType");
        }
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
