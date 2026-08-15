package com.aicostops.ingestion.api;

import com.aicostops.ingestion.application.ImportWorkflowReadModels.AttemptSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.IssueSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.KeySummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.RawRecordDetail;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.RawRecordSummary;
import java.time.Instant;
import java.util.List;

/**
 * Browser-facing Import workflow DTOs. Every identifier is a decimal string;
 * nullable identifiers map to JSON {@code null}, never the literal string
 * {@code "null"}. Worker lease internals and object-store keys are absent by
 * construction.
 */
public final class ImportWorkflowResponses {

    private ImportWorkflowResponses() {
    }

    public record ImportResponse(
            String id,
            EvidenceRef evidence,
            ProviderAccountRef providerAccount,
            String expectedProviderCode,
            String sourceType,
            String parserVersion,
            String status,
            Instant periodStart,
            Instant periodEnd,
            AttemptResponse latestAttempt,
            String createdByMemberId,
            Instant createdAt,
            Instant updatedAt,
            boolean retryable,
            boolean cancelable) {

        public static ImportResponse from(ImportSummary summary) {
            return new ImportResponse(
                    Long.toString(summary.id()),
                    new EvidenceRef(Long.toString(summary.evidence().id()),
                            summary.evidence().originalFilename()),
                    new ProviderAccountRef(Long.toString(summary.providerAccount().id()),
                            summary.providerAccount().displayName()),
                    summary.expectedProviderCode(),
                    summary.sourceType() == null ? null : summary.sourceType().name(),
                    summary.parserVersion(),
                    summary.status() == null ? null : summary.status().name(),
                    summary.periodStart(),
                    summary.periodEnd(),
                    summary.latestAttempt() == null ? null : AttemptResponse.from(summary.latestAttempt()),
                    Long.toString(summary.createdByMemberId()),
                    summary.createdAt(),
                    summary.updatedAt(),
                    summary.retryable(),
                    summary.cancelable());
        }
    }

    public record EvidenceRef(String id, String originalFilename) {
    }

    public record ProviderAccountRef(String id, String displayName) {
    }

    public record AttemptResponse(
            String id,
            int attemptNo,
            String status,
            String triggerType,
            String predecessorAttemptId,
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

        public static AttemptResponse from(AttemptSummary attempt) {
            return new AttemptResponse(
                    Long.toString(attempt.id()),
                    attempt.attemptNo(),
                    attempt.status() == null ? null : attempt.status().name(),
                    attempt.triggerType() == null ? null : attempt.triggerType().name(),
                    attempt.predecessorAttemptId() == null ? null : Long.toString(attempt.predecessorAttemptId()),
                    attempt.parserVersion(),
                    attempt.detectedProviderCode(),
                    attempt.schemaFingerprint(),
                    attempt.startedAt(),
                    attempt.finishedAt(),
                    attempt.createdAt(),
                    attempt.recordsSeen(),
                    attempt.recordsValid(),
                    attempt.warningCount(),
                    attempt.errorCount(),
                    attempt.errorCode(),
                    attempt.errorSummary());
        }
    }

    public record IssueResponse(
            String id,
            String rawProviderRecordId,
            String severity,
            String issueCode,
            String recordLocator,
            String fieldName,
            String message,
            String rawValueMasked,
            Instant createdAt) {

        public static IssueResponse from(IssueSummary issue) {
            return new IssueResponse(
                    Long.toString(issue.id()),
                    issue.rawProviderRecordId() == null ? null : Long.toString(issue.rawProviderRecordId()),
                    issue.severity() == null ? null : issue.severity().name(),
                    issue.issueCode(),
                    issue.recordLocator(),
                    issue.fieldName(),
                    issue.message(),
                    issue.rawValueMasked(),
                    issue.createdAt());
        }
    }

    public record RawRecordSummaryResponse(
            String id,
            long recordIndex,
            String recordLocator,
            String providerRecordKey,
            String normalizeStatus,
            Instant usageStart,
            Instant usageEnd,
            KeySummaryResponse rawPayloadKeys,
            KeySummaryResponse normalizedPayloadKeys,
            Instant createdAt) {

        public static RawRecordSummaryResponse from(RawRecordSummary summary) {
            return new RawRecordSummaryResponse(
                    Long.toString(summary.id()),
                    summary.recordIndex(),
                    summary.recordLocator(),
                    summary.providerRecordKey(),
                    summary.normalizeStatus() == null ? null : summary.normalizeStatus().name(),
                    summary.usageStart(),
                    summary.usageEnd(),
                    KeySummaryResponse.from(summary.rawPayloadKeys()),
                    KeySummaryResponse.from(summary.normalizedPayloadKeys()),
                    summary.createdAt());
        }
    }

    public record KeySummaryResponse(int keyCount, List<String> keys, boolean keysTruncated) {

        public static KeySummaryResponse from(KeySummary summary) {
            return new KeySummaryResponse(summary.keyCount(), List.copyOf(summary.keys()), summary.keysTruncated());
        }
    }

    public record RawRecordDetailResponse(
            String id,
            long recordIndex,
            String recordLocator,
            String providerRecordKey,
            String normalizeStatus,
            Instant usageStart,
            Instant usageEnd,
            Object rawPayload,
            Object normalizedPayload,
            Instant createdAt) {

        public static RawRecordDetailResponse from(RawRecordDetail detail) {
            return new RawRecordDetailResponse(
                    Long.toString(detail.id()),
                    detail.recordIndex(),
                    detail.recordLocator(),
                    detail.providerRecordKey(),
                    detail.normalizeStatus() == null ? null : detail.normalizeStatus().name(),
                    detail.usageStart(),
                    detail.usageEnd(),
                    detail.rawPayload(),
                    detail.normalizedPayload(),
                    detail.createdAt());
        }
    }
}
