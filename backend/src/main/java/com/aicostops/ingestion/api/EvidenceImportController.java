package com.aicostops.ingestion.api;

import com.aicostops.ingestion.api.ImportWorkflowResponses.ImportResponse;
import com.aicostops.ingestion.application.ImportWorkflowQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code GET /api/v1/evidence/{evidenceId}/imports} from the ingestion
 * module so the evidence module never depends on ingestion.
 */
@RestController
public class EvidenceImportController {

    private final ImportWorkflowQueryService queries;

    public EvidenceImportController(ImportWorkflowQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/evidence/{evidenceId}/imports")
    public PageResponse<ImportResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long evidenceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.listEvidenceImports(authenticatedUser, evidenceId, page, size);
        return new PageResponse<>(
                result.items().stream().map(ImportResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }
}
