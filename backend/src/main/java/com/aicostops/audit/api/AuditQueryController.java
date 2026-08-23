package com.aicostops.audit.api;

import com.aicostops.audit.application.AuditEventView;
import com.aicostops.audit.application.AuditQueryService;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only audit event query API (AIC-065). Ids serialize as strings, the
 * stored metadata JSON is re-parsed so callers receive real JSON, and times
 * follow the shared UTC ISO-8601 convention.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditQueryController {

    private final AuditQueryService queries;
    private final ObjectMapper objectMapper;

    public AuditQueryController(AuditQueryService queries, ObjectMapper objectMapper) {
        this.queries = queries;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public PageResponse<AuditEventResponse> search(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam long orgId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.search(authenticatedUser, parsePage(page, size),
                orgId, eventType, from, to);
        return new PageResponse<>(
                result.items().stream().map(this::toResponse).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    private AuditEventResponse toResponse(AuditEventView view) {
        return new AuditEventResponse(
                ApiId.of(view.id()),
                view.organizationId() == null ? null : ApiId.of(view.organizationId()),
                view.actorUserId() == null ? null : ApiId.of(view.actorUserId()),
                view.eventType(),
                view.subjectType(),
                view.subjectId() == null ? null : ApiId.of(view.subjectId()),
                // Re-parsing keeps the API shape JSON-native instead of
                // leaking the database column's string representation.
                objectMapper.readTree(view.metadataJson()),
                view.createdAt());
    }

    /**
     * Mirrors the ledger controllers: an out-of-range page/size is a 400
     * VALIDATION_FAILED problem rather than a framework 500.
     */
    private static PageRequest parsePage(int page, int size) {
        try {
            return PageRequest.of(page, size);
        } catch (IllegalArgumentException invalidPage) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid audit pagination", invalidPage.getMessage());
        }
    }

    public record AuditEventResponse(
            ApiId id,
            ApiId orgId,
            ApiId actorUserId,
            String eventType,
            String subjectType,
            ApiId subjectId,
            Object metadata,
            Instant createdAt) {
    }
}
