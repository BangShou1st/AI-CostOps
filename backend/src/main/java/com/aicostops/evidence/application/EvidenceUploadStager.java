package com.aicostops.evidence.application;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;

/**
 * Streams upload bytes to a bounded temporary file while computing SHA-256 and size.
 *
 * <p>A complete file is never loaded into JVM heap: reading uses a fixed 64 KiB
 * buffer and an explicit byte counter. The temporary file is deleted on every
 * failure path and by the caller in success cleanup.
 */
public class EvidenceUploadStager {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final long uploadLimitBytes;

    public EvidenceUploadStager(long uploadLimitBytes) {
        if (uploadLimitBytes <= 0) {
            throw new IllegalArgumentException("uploadLimitBytes must be positive");
        }
        this.uploadLimitBytes = uploadLimitBytes;
    }

    public StagedEvidence stage(InputStream content) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("aicostops-evidence-upload-", ".bin");
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[BUFFER_SIZE];
            long sizeBytes = 0;
            try (var out = Files.newOutputStream(tempFile)) {
                int read;
                while ((read = content.read(buffer)) != -1) {
                    sizeBytes += read;
                    if (sizeBytes > uploadLimitBytes) {
                        throw tooLarge();
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            var sha256 = HexFormat.of().formatHex(digest.digest());
            return new StagedEvidence(sha256, sizeBytes, tempFile);
        } catch (DomainException exception) {
            deleteQuietly(tempFile);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(tempFile);
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            deleteQuietly(tempFile);
            throw new IllegalStateException("SHA-256 must be available", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(tempFile);
            throw exception;
        }
    }

    private DomainException tooLarge() {
        return new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, ProblemCode.EVIDENCE_TOO_LARGE,
                "Evidence exceeds the upload limit",
                "The uploaded evidence exceeds the configured storage upload limit.");
    }

    private static void deleteQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best-effort cleanup; the OS temp directory remains the final fallback.
            }
        }
    }
}
