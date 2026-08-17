package com.aicostops.expense.api;

import com.aicostops.expense.api.ExpenseResponses.ExpenseResponse;
import com.aicostops.expense.application.ExpenseEvidenceDownloadService;
import com.aicostops.expense.application.ExpenseEvidenceUploadService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Expense primary-evidence endpoints: owner upload (multipart + expectedVersion
 * CAS) and expense-scoped download for the owner and for finance review.
 */
@RestController
@RequestMapping("/api/v1/expenses/{expenseId}/evidence")
public class ExpenseEvidenceController {

    private final ExpenseEvidenceUploadService uploads;
    private final ExpenseEvidenceDownloadService downloads;

    public ExpenseEvidenceController(
            ExpenseEvidenceUploadService uploads,
            ExpenseEvidenceDownloadService downloads) {
        this.uploads = uploads;
        this.downloads = downloads;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExpenseResponse attach(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId,
            @RequestParam("expectedVersion") long expectedVersion,
            @RequestPart("file") MultipartFile file) {
        try (var content = file.getInputStream()) {
            var detail = uploads.attach(authenticatedUser, expenseId, expectedVersion,
                    file.getOriginalFilename(), file.getContentType(), content);
            return ExpenseResponse.from(detail, detail.claimantMemberId());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId) {
        var download = downloads.download(authenticatedUser, expenseId);
        return streamResponse(download);
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(
            com.aicostops.evidence.application.EvidenceDownloadService.EvidenceDownload download) {
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