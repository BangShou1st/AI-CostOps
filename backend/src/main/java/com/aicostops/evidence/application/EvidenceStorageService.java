package com.aicostops.evidence.application;

import com.aicostops.evidence.domain.Evidence;
import com.aicostops.evidence.domain.EvidenceStorageStatus;
import com.aicostops.evidence.infrastructure.EvidenceStorageProperties;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Streaming Evidence storage workflow.
 *
 * <p>{@code stage -> short DB reserve/reuse -> object storage outside any DB transaction
 * -> short DB finalize}. An {@code AVAILABLE} Evidence is never downgraded, a
 * deterministic-key metadata mismatch is a conflict that never overwrites bytes, and
 * a {@code STAGING} row with a matching stored object is repaired to {@code AVAILABLE}.
 */
@Service
public class EvidenceStorageService {

    private final EvidencePersistenceService persistence;
    private final ObjectStoragePort storage;
    private final EvidenceUploadStager stager;
    private final Clock clock;

    public EvidenceStorageService(
            EvidencePersistenceService persistence,
            ObjectStoragePort storage,
            EvidenceStorageProperties properties,
            Clock clock) {
        this.persistence = persistence;
        this.storage = storage;
        this.stager = new EvidenceUploadStager(properties.uploadLimit().toBytes());
        this.clock = clock;
    }

    public Evidence store(
            long organizationId,
            long uploadedByMemberId,
            String originalFilename,
            String mediaType,
            InputStream content) {
        var staged = stager.stage(content);
        try {
            return storeStaged(organizationId, uploadedByMemberId, originalFilename, mediaType, staged);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(staged.tempFile());
            } catch (java.io.IOException ignored) {
                // Best-effort cleanup; OS temp directory remains the final fallback.
            }
        }
    }

    private Evidence storeStaged(
            long organizationId,
            long uploadedByMemberId,
            String originalFilename,
            String mediaType,
            StagedEvidence staged) {
        var objectKey = objectKey(organizationId, staged.sha256());
        var now = clock.instant();
        var reserved = persistence.reserveOrReuse(
                organizationId, staged.sha256(), objectKey, originalFilename, mediaType,
                staged.sizeBytes(), uploadedByMemberId, now);
        if (reserved.storageStatus() == EvidenceStorageStatus.AVAILABLE) {
            return reserved;
        }

        var existing = storage.stat(objectKey);
        if (existing.isPresent()) {
            var metadata = existing.get();
            if (Objects.equals(metadata.sha256(), staged.sha256())
                    && metadata.sizeBytes() == staged.sizeBytes()) {
                persistence.markAvailable(reserved.id(), organizationId, now);
            } else {
                throw metadataConflict();
            }
        } else {
            try {
                storage.put(objectKey, staged.tempFile(), staged.sizeBytes(), staged.sha256());
            } catch (RuntimeException failure) {
                persistence.markFailedUnlessAvailable(
                        reserved.id(), organizationId, "STORAGE_UPLOAD_FAILED", now);
                throw failure;
            }
            persistence.markAvailable(reserved.id(), organizationId, now);
        }
        return persistence.findByIdAndOrganization(reserved.id(), organizationId).orElseThrow();
    }

    /**
     * Deterministic tenant-scoped key; no filename, email, token or secret participates.
     */
    static String objectKey(long organizationId, String sha256) {
        return "org/" + organizationId + "/evidence/sha256/" + sha256.substring(0, 2) + "/" + sha256;
    }

    private DomainException metadataConflict() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Evidence object metadata conflict",
                "An object already exists at the deterministic key with different bytes; refusing to overwrite.");
    }
}
