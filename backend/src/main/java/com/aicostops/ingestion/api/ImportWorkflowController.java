package com.aicostops.ingestion.api;

import com.aicostops.ingestion.api.ImportWorkflowResponses.AttemptResponse;
import com.aicostops.ingestion.api.ImportWorkflowResponses.ImportResponse;
import com.aicostops.ingestion.api.ImportWorkflowResponses.IssueResponse;
import com.aicostops.ingestion.api.ImportWorkflowResponses.RawRecordDetailResponse;
import com.aicostops.ingestion.api.ImportWorkflowResponses.RawRecordSummaryResponse;
import com.aicostops.ingestion.application.ImportWorkflowCommandService;
import com.aicostops.ingestion.application.ImportWorkflowQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportWorkflowController {

    private final ImportWorkflowQueryService queries;
    private final ImportWorkflowCommandService commands;

    public ImportWorkflowController(ImportWorkflowQueryService queries, ImportWorkflowCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @PostMapping("/{importId}/retry")
    public ImportResponse retry(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ImportResponse.from(commands.retry(authenticatedUser, importId, idempotencyKey));
    }

    @PostMapping("/{importId}/cancel")
    public ImportResponse cancel(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ImportResponse.from(commands.cancel(authenticatedUser, importId, idempotencyKey));
    }

    @PostMapping("/{importId}/confirm")
    public ImportResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ImportResponse.from(commands.confirm(authenticatedUser, importId, idempotencyKey));
    }

    @GetMapping
    public PageResponse<ImportResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long providerAccountId) {
        var result = queries.listImports(authenticatedUser, page, size, status, providerAccountId);
        return map(result.items().stream().map(ImportResponse::from).toList(), result);
    }

    @GetMapping("/{importId}")
    public ImportResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId) {
        return ImportResponse.from(queries.getImport(authenticatedUser, importId));
    }

    @GetMapping("/{importId}/attempts")
    public PageResponse<AttemptResponse> attempts(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.listAttempts(authenticatedUser, importId, page, size);
        return map(result.items().stream().map(AttemptResponse::from).toList(), result);
    }

    @GetMapping("/{importId}/attempts/{attemptId}/issues")
    public PageResponse<IssueResponse> issues(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @PathVariable long attemptId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String issueCode) {
        var result = queries.listIssues(authenticatedUser, importId, attemptId, page, size, severity, issueCode);
        return map(result.items().stream().map(IssueResponse::from).toList(), result);
    }

    @GetMapping("/{importId}/attempts/{attemptId}/raw-records")
    public PageResponse<RawRecordSummaryResponse> rawRecords(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @PathVariable long attemptId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String normalizeStatus) {
        var result = queries.listRawRecords(authenticatedUser, importId, attemptId, page, size, normalizeStatus);
        return map(result.items().stream().map(RawRecordSummaryResponse::from).toList(), result);
    }

    @GetMapping("/{importId}/attempts/{attemptId}/raw-records/{recordId}")
    public RawRecordDetailResponse rawRecord(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long importId,
            @PathVariable long attemptId,
            @PathVariable long recordId) {
        return RawRecordDetailResponse.from(
                queries.getRawRecord(authenticatedUser, importId, attemptId, recordId));
    }

    private static <T, R> PageResponse<R> map(java.util.List<R> items, PageResponse<T> result) {
        return new PageResponse<>(items, result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
