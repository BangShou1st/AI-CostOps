package com.aicostops.ingestion.api;

import com.aicostops.ingestion.application.ProviderImportService;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.shared.security.AuthenticatedUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/provider-imports")
public class ProviderImportController {

    private final ProviderImportService imports;

    public ProviderImportController(ProviderImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderImportResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestPart("file") MultipartFile file,
            @RequestParam("providerAccountId") long providerAccountId,
            @RequestParam("sourceType") ImportSourceType sourceType) {
        try (var content = file.getInputStream()) {
            var result = imports.create(authenticatedUser, file.getOriginalFilename(), file.getContentType(),
                    content, providerAccountId, sourceType);
            return ProviderImportResponse.of(
                    result.evidenceId(), result.importBatchId(), result.latestAttemptId(),
                    result.batchStatus(), result.duplicateEvidence(), result.duplicateBatch());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
