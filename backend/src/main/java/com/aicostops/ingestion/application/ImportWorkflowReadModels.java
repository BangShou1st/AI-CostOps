package com.aicostops.ingestion.application;

import com.aicostops.ingestion.domain.ImportAttemptStatus;
import com.aicostops.ingestion.domain.ImportAttemptTrigger;
import com.aicostops.ingestion.domain.ImportBatchStatus;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import java.time.Instant;
import java.util.List;

/**
 * Immutable read models for the Evidence / Import review workflow.
 *
 * <p>Identifiers remain {@code long} / nullable {@code Long} at this layer
 * (browser-safe decimal strings are produced only by HTTP response DTOs).
 */
public final class ImportWorkflowReadModels {

    private ImportWorkflowReadModels() {
    }

    public record ImportSummary(
            long id,
            EvidenceRef evidence,
            ProviderAccountRef providerAccount,
            String expectedProviderCode,
            ImportSourceType sourceType,
            String parserVersion,
            ImportBatchStatus status,
            Instant periodStart,
            Instant periodEnd,
            AttemptSummary latestAttempt,
            long createdByMemberId,
            Instant createdAt,
            Instant updatedAt,
            Long confirmedAttemptId,
            boolean retryable,
            boolean cancelable) {

        public static ImportSummary of(
                long id,
                long evidenceId,
                String evidenceOriginalFilename,
                long providerAccountId,
                String providerDisplayName,
                String expectedProviderCode,
                ImportSourceType sourceType,
                String parserVersion,
                ImportBatchStatus status,
                Instant periodStart,
                Instant periodEnd,
                AttemptSummary latestAttempt,
                long createdByMemberId,
                Instant createdAt,
                Instant updatedAt,
                Long confirmedAttemptId) {
            return new ImportSummary(
                    id,
                    new EvidenceRef(evidenceId, evidenceOriginalFilename),
                    new ProviderAccountRef(providerAccountId, providerDisplayName),
                    expectedProviderCode,
                    sourceType,
                    parserVersion,
                    status,
                    periodStart,
                    periodEnd,
                    latestAttempt,
                    createdByMemberId,
                    createdAt,
                    updatedAt,
                    confirmedAttemptId,
                    status == ImportBatchStatus.FAILED || status == ImportBatchStatus.CANCELED,
                    (status == ImportBatchStatus.PENDING && latestAttempt != null
                            && latestAttempt.status() == ImportAttemptStatus.QUEUED)
                            || (status == ImportBatchStatus.PROCESSING && latestAttempt != null
                            && latestAttempt.status() == ImportAttemptStatus.RUNNING)
                            || (status == ImportBatchStatus.FAILED && latestAttempt != null
                            && latestAttempt.status() == ImportAttemptStatus.FAILED));
        }
    }

    public record EvidenceRef(long id, String originalFilename) {
    }

    public record ProviderAccountRef(long id, String displayName) {
    }

    public record AttemptSummary(
            long id,
            int attemptNo,
            ImportAttemptStatus status,
            ImportAttemptTrigger triggerType,
            Long predecessorAttemptId,
            String parserVersion,
            String detectedProviderCode,
            String schemaFingerprint,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            long recordsSeen,
            long recordsValid,
            long warningCount,
            long errorCount,
            String errorCode,
            String errorSummary) {
    }

    public record IssueSummary(
            long id,
            Long rawProviderRecordId,
            ImportIssueSeverity severity,
            String issueCode,
            String recordLocator,
            String fieldName,
            String message,
            String rawValueMasked,
            Instant createdAt) {
    }

    public record RawRecordSummary(
            long id,
            long recordIndex,
            String recordLocator,
            String providerRecordKey,
            RawRecordNormalizeStatus normalizeStatus,
            Instant usageStart,
            Instant usageEnd,
            KeySummary rawPayloadKeys,
            KeySummary normalizedPayloadKeys,
            Instant createdAt) {
    }

    /** Bounded top-level JSON key summary; never carries payload values. */
    public record KeySummary(int keyCount, List<String> keys, boolean keysTruncated) {
    }

    public record RawRecordDetail(
            long id,
            long recordIndex,
            String recordLocator,
            String providerRecordKey,
            RawRecordNormalizeStatus normalizeStatus,
            Instant usageStart,
            Instant usageEnd,
            Object rawPayload,
            Object normalizedPayload,
            Instant createdAt) {
    }
}
