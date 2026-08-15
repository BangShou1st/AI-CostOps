package com.aicostops.evidence.api;

import com.aicostops.evidence.application.EvidenceDownloadService;
import com.aicostops.evidence.application.EvidenceQueryService;
import com.aicostops.evidence.application.EvidenceQueryService.EvidenceSummary;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class EvidenceController {

    private final EvidenceDownloadService downloads;
    private final EvidenceQueryService queries;

    public EvidenceController(EvidenceDownloadService downloads, EvidenceQueryService queries) {
        this.downloads = downloads;
        this.queries = queries;
    }

    @GetMapping("/api/v1/evidence")
    public PageResponse<EvidenceResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.list(authenticatedUser, page, size);
        return new PageResponse<>(
                result.items().stream().map(EvidenceResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/api/v1/evidence/{evidenceId}")
    public EvidenceResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long evidenceId) {
        return EvidenceResponse.from(queries.get(authenticatedUser, evidenceId));
    }

    @GetMapping("/api/v1/evidence/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id) {
        var download = downloads.download(authenticatedUser, id);
        var evidence = download.evidence();
        var mediaType = evidence.mediaType() == null || evidence.mediaType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : evidence.mediaType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFilename(evidence.originalFilename()) + "\"")
                .body(out -> {
                    try (var in = download.stream()) {
                        in.transferTo(out);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "evidence.bin";
        }
        return filename.replaceAll("[\\r\\n\\\"\\\\/]", "_").strip();
    }

    /**
     * Browser-facing Evidence DTO: identifiers are decimal strings. The internal
     * object-store key is never part of this contract.
     */
    public record EvidenceResponse(
            String id,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            String sha256,
            String storageStatus,
            String storageErrorCode,
            String uploadedByMemberId,
            Instant createdAt,
            Instant updatedAt) {

        static EvidenceResponse from(EvidenceSummary summary) {
            return new EvidenceResponse(
                    Long.toString(summary.id()),
                    summary.originalFilename(),
                    summary.mediaType(),
                    summary.sizeBytes(),
                    summary.sha256(),
                    summary.storageStatus(),
                    summary.storageErrorCode(),
                    Long.toString(summary.uploadedByMemberId()),
                    summary.createdAt(),
                    summary.updatedAt());
        }
    }
}
