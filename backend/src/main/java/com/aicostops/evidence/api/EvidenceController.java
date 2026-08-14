package com.aicostops.evidence.api;

import com.aicostops.evidence.application.EvidenceDownloadService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class EvidenceController {

    private final EvidenceDownloadService downloads;

    public EvidenceController(EvidenceDownloadService downloads) {
        this.downloads = downloads;
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
}
