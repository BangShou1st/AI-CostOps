package com.aicostops.ingestion.application;

import com.aicostops.evidence.infrastructure.EvidenceMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.AttemptSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.IssueSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.KeySummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.RawRecordDetail;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.RawRecordSummary;
import com.aicostops.ingestion.domain.ImportAttemptStatus;
import com.aicostops.ingestion.domain.ImportAttemptTrigger;
import com.aicostops.ingestion.domain.ImportBatchStatus;
import com.aicostops.ingestion.domain.ImportIssue;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.ingestion.domain.RawProviderRecord;
import com.aicostops.ingestion.infrastructure.ImportWorkflowQueryMapper;
import com.aicostops.ingestion.infrastructure.ImportWorkflowQueryMapper.ImportReviewRow;
import com.aicostops.ingestion.infrastructure.ImportWorkflowQueryMapper.RawRecordReviewRow;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Authorized review reads over the Evidence → ImportBatch → ImportAttempt →
 * Issue/RawRecord lineage. Every method requires {@code IMPORT_READ} at the
 * current organization first; resource lookups are always org-scoped and
 * parent-child mismatches resolve to privacy-preserving 404s.
 */
@Service
public class ImportWorkflowQueryService {

    // Derived from ImportBatchStatus so the list filter can never drift from the
    // persisted domain statuses again (UAT found CONFIRMED/READY_FOR_REVIEW missing).
    private static final Set<String> VALID_IMPORT_STATUSES = Arrays.stream(ImportBatchStatus.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> VALID_ISSUE_SEVERITIES = Set.of("WARN", "ERROR");
    private static final Set<String> VALID_NORMALIZE_STATUSES = Set.of("NORMALIZED", "WARN", "ERROR");

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ImportWorkflowQueryMapper queryMapper;
    private final EvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public ImportWorkflowQueryService(
            AuthorizationContextService authorizationContexts,
            ImportWorkflowQueryMapper queryMapper,
            EvidenceMapper evidenceMapper,
            ObjectMapper objectMapper) {
        this.authorizationContexts = authorizationContexts;
        this.queryMapper = queryMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    public PageResponse<ImportSummary> listImports(
            AuthenticatedUser user, int page, int size, String status, Long providerAccountId) {
        var context = authorizedContext(user);
        var pageRequest = validPage(page, size);
        if (status != null && !VALID_IMPORT_STATUSES.contains(status)) {
            throw validationFailed("Import status is invalid",
                    "Import status must be one of " + String.join(", ", VALID_IMPORT_STATUSES) + ".");
        }
        var offset = (long) pageRequest.page() * pageRequest.size();
        var rows = queryMapper.pageImports(context.organizationId(), status, providerAccountId,
                offset, pageRequest.size());
        var total = queryMapper.countImports(context.organizationId(), status, providerAccountId);
        return PageResponse.of(rows.stream().map(ImportWorkflowQueryService::mapImportSummary).toList(), pageRequest, total);
    }

    public ImportSummary getImport(AuthenticatedUser user, long importId) {
        var context = authorizedContext(user);
        var row = orgScopedImport(context.organizationId(), importId);
        return mapImportSummary(row);
    }

    public PageResponse<ImportSummary> listEvidenceImports(
            AuthenticatedUser user, long evidenceId, int page, int size) {
        var context = authorizedContext(user);
        var pageRequest = validPage(page, size);
        if (evidenceMapper.findByIdAndOrganization(evidenceId, context.organizationId()) == null) {
            throw notFound("Evidence not found",
                    "The evidence is not available in the current organization.");
        }
        var offset = (long) pageRequest.page() * pageRequest.size();
        var rows = queryMapper.pageEvidenceImports(
                context.organizationId(), evidenceId, offset, pageRequest.size());
        var total = queryMapper.countEvidenceImports(context.organizationId(), evidenceId);
        return PageResponse.of(rows.stream().map(ImportWorkflowQueryService::mapImportSummary).toList(), pageRequest, total);
    }

    public PageResponse<AttemptSummary> listAttempts(
            AuthenticatedUser user, long importId, int page, int size) {
        var context = authorizedContext(user);
        var pageRequest = validPage(page, size);
        orgScopedImport(context.organizationId(), importId);
        var offset = (long) pageRequest.page() * pageRequest.size();
        var attempts = queryMapper.pageAttempts(importId, offset, pageRequest.size());
        var total = queryMapper.countAttempts(importId);
        return PageResponse.of(attempts.stream().map(ImportWorkflowQueryService::toAttemptSummary).toList(),
                pageRequest, total);
    }

    public PageResponse<IssueSummary> listIssues(
            AuthenticatedUser user, long importId, long attemptId,
            int page, int size, String severity, String issueCode) {
        var context = authorizedContext(user);
        var pageRequest = validPage(page, size);
        orgScopedImport(context.organizationId(), importId);
        requireAttemptOfBatch(attemptId, importId);
        if (severity != null && !VALID_ISSUE_SEVERITIES.contains(severity)) {
            throw validationFailed("Issue severity is invalid", "Issue severity must be WARN or ERROR.");
        }
        var offset = (long) pageRequest.page() * pageRequest.size();
        var issues = queryMapper.pageIssues(attemptId, severity, issueCode, offset, pageRequest.size());
        var total = queryMapper.countIssues(attemptId, severity, issueCode);
        return PageResponse.of(issues.stream().map(this::toIssueSummary).toList(), pageRequest, total);
    }

    public PageResponse<RawRecordSummary> listRawRecords(
            AuthenticatedUser user, long importId, long attemptId,
            int page, int size, String normalizeStatus) {
        var context = authorizedContext(user);
        var pageRequest = validPage(page, size);
        orgScopedImport(context.organizationId(), importId);
        requireAttemptOfBatch(attemptId, importId);
        if (normalizeStatus != null && !VALID_NORMALIZE_STATUSES.contains(normalizeStatus)) {
            throw validationFailed("Normalize status is invalid",
                    "Normalize status must be one of NORMALIZED, WARN, ERROR.");
        }
        var offset = (long) pageRequest.page() * pageRequest.size();
        var rows = queryMapper.pageRawRecords(attemptId, normalizeStatus, offset, pageRequest.size());
        var total = queryMapper.countRawRecords(attemptId, normalizeStatus);
        return PageResponse.of(rows.stream().map(this::toRawRecordSummary).toList(), pageRequest, total);
    }

    public RawRecordDetail getRawRecord(AuthenticatedUser user, long importId, long attemptId, long recordId) {
        var context = authorizedContext(user);
        orgScopedImport(context.organizationId(), importId);
        requireAttemptOfBatch(attemptId, importId);
        var record = queryMapper.findRawRecordByIdAndAttempt(recordId, attemptId);
        if (record == null) {
            throw notFound("Raw record not found",
                    "The raw record is not available for this import attempt.");
        }
        return new RawRecordDetail(
                record.id(),
                record.recordIndex(),
                safeLocator(record.recordLocator()),
                safeRecordKey(record.providerRecordKey()),
                record.normalizeStatus(),
                record.usageStart(),
                record.usageEnd(),
                redactJson(record.rawPayload()),
                redactJson(record.normalizedPayload()),
                record.createdAt());
    }

    private com.aicostops.iam.domain.AuthorizationContext authorizedContext(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "IMPORT_READ");
        return context;
    }

    private ImportReviewRow orgScopedImport(long organizationId, long importId) {
        var row = queryMapper.findImportByIdAndOrganization(importId, organizationId);
        if (row == null) {
            throw notFound("Import not found",
                    "The import is not available in the current organization.");
        }
        return row;
    }

    private void requireAttemptOfBatch(long attemptId, long importId) {
        if (queryMapper.countAttemptOfBatch(attemptId, importId) == 0) {
            throw notFound("Import attempt not found",
                    "The import attempt is not available for this import.");
        }
    }

    static ImportSummary mapImportSummary(ImportReviewRow row) {
        var latest = row.latestAttemptId() == null ? null : toAttemptSummary(
                new com.aicostops.ingestion.domain.ImportAttempt(
                        row.latestAttemptId(), row.id(), row.latestAttemptNo(), attemptStatusOf(row.latestAttemptStatus()),
                        triggerOf(row.latestAttemptTrigger()), row.latestPredecessorAttemptId(), null, null, null,
                        0, row.latestParserVersion(), row.latestDetectedProviderCode(),
                        row.latestSchemaFingerprint(), row.latestStartedAt(), row.latestFinishedAt(),
                        row.latestErrorCode(), row.latestErrorSummary(), row.latestRecordsSeen(),
                        row.latestRecordsValid(), row.latestWarningCount(), row.latestErrorCount(),
                        row.latestCreatedAt()));
        return ImportSummary.of(
                row.id(), row.evidenceId(), row.evidenceOriginalFilename(),
                row.providerAccountId(), row.providerDisplayName(),
                row.expectedProviderCode(), sourceTypeOf(row.sourceType()), row.parserVersion(),
                statusOf(row.status()), row.periodStart(), row.periodEnd(), latest,
                row.createdByMemberId(), row.createdAt(), row.updatedAt(), row.confirmedAttemptId());
    }

    private static AttemptSummary toAttemptSummary(com.aicostops.ingestion.domain.ImportAttempt attempt) {
        return new AttemptSummary(
                attempt.id(), attempt.attemptNo(), attempt.status(), attempt.triggerType(),
                attempt.predecessorAttemptId(), attempt.parserVersion(), attempt.detectedProviderCode(),
                attempt.schemaFingerprint(), attempt.startedAt(), attempt.finishedAt(), attempt.createdAt(),
                attempt.recordsSeen(), attempt.recordsValid(), attempt.warningCount(), attempt.errorCount(),
                attempt.errorCode(), attempt.errorSummary());
    }

    private IssueSummary toIssueSummary(ImportIssue issue) {
        return new IssueSummary(
                issue.id(), issue.rawProviderRecordId(), issue.severity(), issue.issueCode(),
                issue.recordLocator(), issue.fieldName(), issue.message(), issue.rawValueMasked(),
                issue.createdAt());
    }

    private RawRecordSummary toRawRecordSummary(RawRecordReviewRow row) {
        return new RawRecordSummary(
                row.id(), row.recordIndex(), safeLocator(row.recordLocator()), safeRecordKey(row.providerRecordKey()),
                normalizeStatusOf(row.normalizeStatus()), row.usageStart(), row.usageEnd(),
                keySummary(row.rawKeyCount(), row.rawKeysPreview()),
                keySummary(row.normalizedKeyCount(), row.normalizedKeysPreview()),
                row.createdAt());
    }

    private KeySummary keySummary(int keyCount, String keysPreview) {
        if (keyCount <= 32) {
            return new KeySummary(keyCount, sanitizeKeys(parseKeyList(keysPreview)), false);
        }
        return new KeySummary(keyCount, List.of(), true);
    }

    /**
     * Defense-in-depth at the read boundary: legacy rows persisted before
     * key-level sanitization existed may still carry secret-shaped JSON keys
     * (e.g. {@code sk-...}, {@code api_key=sk-...}); the list key summary never
     * returns them raw. Sanitization is idempotent, so already-safe keys pass
     * through unchanged.
     */
    private List<String> sanitizeKeys(List<String> keys) {
        return keys.stream().map(PayloadRedactor::sanitizeKey).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseKeyList(String keysPreview) {
        if (keysPreview == null || keysPreview.isBlank()) {
            return List.of();
        }
        try {
            var parsed = objectMapper.readValue(keysPreview, List.class);
            return (List<String>) parsed;
        } catch (Exception exception) {
            throw new IllegalStateException("Stored raw key preview is not a valid JSON array", exception);
        }
    }

    private Object redactJson(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            var parsed = objectMapper.readValue(payload, Object.class);
            return PayloadRedactor.redact(parsed);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored raw payload is not valid JSON", exception);
        }
    }

    /**
     * Defense-in-depth at the read boundary: metadata persisted before the
     * persistence-time sanitization existed (or bypassing it) is redacted again
     * here. Sanitization is idempotent, so already-safe values pass through.
     */
    private static String safeLocator(String locator) {
        return IssueSanitizer.sanitizeLocator(locator);
    }

    private static String safeRecordKey(String providerRecordKey) {
        return IssueSanitizer.sanitizeRecordKey(providerRecordKey);
    }

    private static ImportBatchStatus statusOf(String status) {
        return status == null ? null : ImportBatchStatus.valueOf(status);
    }

    private static ImportAttemptStatus attemptStatusOf(String status) {
        return status == null ? null : ImportAttemptStatus.valueOf(status);
    }

    private static ImportAttemptTrigger triggerOf(String trigger) {
        return trigger == null ? null : ImportAttemptTrigger.valueOf(trigger);
    }

    private static ImportSourceType sourceTypeOf(String sourceType) {
        return sourceType == null ? null : ImportSourceType.valueOf(sourceType);
    }

    private static RawRecordNormalizeStatus normalizeStatusOf(String status) {
        return status == null ? null : RawRecordNormalizeStatus.valueOf(status);
    }

    private static PageRequest validPage(int page, int size) {
        try {
            return PageRequest.of(page, size);
        } catch (IllegalArgumentException invalid) {
            throw validationFailed("Invalid page or size",
                    "Page must be zero or greater and size must be between 1 and 200.");
        }
    }

    private static DomainException validationFailed(String title, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED, title, detail);
    }

    private static DomainException notFound(String title, String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND, title, detail);
    }
}
