package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.domain.ImportAttempt;
import com.aicostops.ingestion.domain.ImportIssue;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Bounded review reads for the Import workflow. Every page query uses SQL
 * LIMIT/OFFSET and never materializes full payload values; raw-list projections
 * return only a bounded top-level JSON key summary.
 */
@Mapper
public interface ImportWorkflowQueryMapper {

    String BATCH_REVIEW_COLUMNS = """
            ib.id,ib.org_id,ib.evidence_id,ib.provider_account_id,ib.expected_provider_code,
            ib.source_type,ib.parser_version,ib.status,ib.period_start,ib.period_end,
            ib.created_by_member_id,ib.created_at,ib.updated_at,
            e.original_filename AS evidence_original_filename,
            pa.display_name AS provider_display_name
            """;

    String LATEST_ATTEMPT_JOIN = """
            LEFT JOIN import_attempt ia ON ia.id = (
                SELECT i2.id FROM import_attempt i2
                WHERE i2.import_batch_id = ib.id
                ORDER BY i2.attempt_no DESC, i2.id DESC
                LIMIT 1)
            """;

    @Select("""
            SELECT
            """ + BATCH_REVIEW_COLUMNS + """
            ,ia.id AS latest_attempt_id,ia.attempt_no AS latest_attempt_no,
             ia.status AS latest_attempt_status,ia.trigger_type AS latest_attempt_trigger,
             ia.predecessor_attempt_id AS latest_predecessor_attempt_id,
             ia.parser_version AS latest_parser_version,
             ia.detected_provider_code AS latest_detected_provider_code,
             ia.schema_fingerprint AS latest_schema_fingerprint,
             ia.started_at AS latest_started_at,ia.finished_at AS latest_finished_at,
             ia.created_at AS latest_created_at,
             ia.records_seen AS latest_records_seen,ia.records_valid AS latest_records_valid,
             ia.warning_count AS latest_warning_count,ia.error_count AS latest_error_count,
             ia.error_code AS latest_error_code,ia.error_summary AS latest_error_summary
            FROM import_batch ib
            JOIN evidence e ON e.id = ib.evidence_id AND e.org_id = ib.org_id
            JOIN provider_account pa ON pa.id = ib.provider_account_id AND pa.org_id = ib.org_id
            """ + LATEST_ATTEMPT_JOIN + """
            WHERE ib.org_id=#{organizationId}
              AND (#{status} IS NULL OR ib.status=#{status})
              AND (#{providerAccountId} IS NULL OR ib.provider_account_id=#{providerAccountId})
            ORDER BY ib.created_at DESC, ib.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ImportReviewRow> pageImports(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("providerAccountId") Long providerAccountId,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM import_batch ib
            JOIN evidence e ON e.id = ib.evidence_id AND e.org_id = ib.org_id
            JOIN provider_account pa ON pa.id = ib.provider_account_id AND pa.org_id = ib.org_id
            WHERE ib.org_id=#{organizationId}
              AND (#{status} IS NULL OR ib.status=#{status})
              AND (#{providerAccountId} IS NULL OR ib.provider_account_id=#{providerAccountId})
            """)
    long countImports(
            @Param("organizationId") long organizationId,
            @Param("status") String status,
            @Param("providerAccountId") Long providerAccountId);

    @Select("""
            SELECT
            """ + BATCH_REVIEW_COLUMNS + """
            ,ia.id AS latest_attempt_id,ia.attempt_no AS latest_attempt_no,
             ia.status AS latest_attempt_status,ia.trigger_type AS latest_attempt_trigger,
             ia.predecessor_attempt_id AS latest_predecessor_attempt_id,
             ia.parser_version AS latest_parser_version,
             ia.detected_provider_code AS latest_detected_provider_code,
             ia.schema_fingerprint AS latest_schema_fingerprint,
             ia.started_at AS latest_started_at,ia.finished_at AS latest_finished_at,
             ia.created_at AS latest_created_at,
             ia.records_seen AS latest_records_seen,ia.records_valid AS latest_records_valid,
             ia.warning_count AS latest_warning_count,ia.error_count AS latest_error_count,
             ia.error_code AS latest_error_code,ia.error_summary AS latest_error_summary
            FROM import_batch ib
            JOIN evidence e ON e.id = ib.evidence_id AND e.org_id = ib.org_id
            JOIN provider_account pa ON pa.id = ib.provider_account_id AND pa.org_id = ib.org_id
            """ + LATEST_ATTEMPT_JOIN + """
            WHERE ib.id=#{batchId} AND ib.org_id=#{organizationId}
            """)
    ImportReviewRow findImportByIdAndOrganization(
            @Param("batchId") long batchId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT
            """ + BATCH_REVIEW_COLUMNS + """
            ,ia.id AS latest_attempt_id,ia.attempt_no AS latest_attempt_no,
             ia.status AS latest_attempt_status,ia.trigger_type AS latest_attempt_trigger,
             ia.predecessor_attempt_id AS latest_predecessor_attempt_id,
             ia.parser_version AS latest_parser_version,
             ia.detected_provider_code AS latest_detected_provider_code,
             ia.schema_fingerprint AS latest_schema_fingerprint,
             ia.started_at AS latest_started_at,ia.finished_at AS latest_finished_at,
             ia.created_at AS latest_created_at,
             ia.records_seen AS latest_records_seen,ia.records_valid AS latest_records_valid,
             ia.warning_count AS latest_warning_count,ia.error_count AS latest_error_count,
             ia.error_code AS latest_error_code,ia.error_summary AS latest_error_summary
            FROM import_batch ib
            JOIN evidence e ON e.id = ib.evidence_id AND e.org_id = ib.org_id
            JOIN provider_account pa ON pa.id = ib.provider_account_id AND pa.org_id = ib.org_id
            """ + LATEST_ATTEMPT_JOIN + """
            WHERE ib.evidence_id=#{evidenceId}
              AND ib.org_id=#{organizationId}
            ORDER BY ib.created_at DESC, ib.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ImportReviewRow> pageEvidenceImports(
            @Param("organizationId") long organizationId,
            @Param("evidenceId") long evidenceId,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM import_batch ib
            JOIN evidence e ON e.id = ib.evidence_id AND e.org_id = ib.org_id
            JOIN provider_account pa ON pa.id = ib.provider_account_id AND pa.org_id = ib.org_id
            WHERE ib.evidence_id=#{evidenceId}
              AND ib.org_id=#{organizationId}
            """)
    long countEvidenceImports(
            @Param("organizationId") long organizationId,
            @Param("evidenceId") long evidenceId);

    @Select("""
            SELECT
            """ + ImportAttemptMapper.IMPORT_ATTEMPT_COLUMNS + """
            FROM import_attempt ia
            WHERE ia.import_batch_id=#{batchId}
            ORDER BY ia.attempt_no DESC, ia.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ImportAttempt> pageAttempts(
            @Param("batchId") long batchId,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM import_attempt ia
            WHERE ia.import_batch_id=#{batchId}
            """)
    long countAttempts(@Param("batchId") long batchId);

    @Select("""
            SELECT ii.id,ii.import_attempt_id,ii.raw_provider_record_id,ii.severity,ii.issue_code,
                   ii.record_locator,ii.field_name,ii.message,ii.raw_value_masked,ii.created_at
            FROM import_issue ii
            WHERE ii.import_attempt_id=#{attemptId}
              AND (#{severity} IS NULL OR ii.severity=#{severity})
              AND (#{issueCode} IS NULL OR ii.issue_code=#{issueCode})
            ORDER BY ii.id ASC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ImportIssue> pageIssues(
            @Param("attemptId") long attemptId,
            @Param("severity") String severity,
            @Param("issueCode") String issueCode,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM import_issue ii
            WHERE ii.import_attempt_id=#{attemptId}
              AND (#{severity} IS NULL OR ii.severity=#{severity})
              AND (#{issueCode} IS NULL OR ii.issue_code=#{issueCode})
            """)
    long countIssues(
            @Param("attemptId") long attemptId,
            @Param("severity") String severity,
            @Param("issueCode") String issueCode);

    @Select("""
            SELECT r.id,r.import_attempt_id,r.record_index,r.record_locator,r.provider_record_key,
                   r.normalize_status,r.usage_start,r.usage_end,r.created_at,
                   JSON_LENGTH(r.raw_payload) AS raw_key_count,
                   CASE WHEN JSON_LENGTH(r.raw_payload) <= 32 THEN JSON_KEYS(r.raw_payload)
                        ELSE JSON_ARRAY() END AS raw_keys_preview,
                   COALESCE(JSON_LENGTH(r.normalized_payload),0) AS normalized_key_count,
                   CASE WHEN JSON_LENGTH(r.normalized_payload) <= 32 THEN JSON_KEYS(r.normalized_payload)
                        ELSE JSON_ARRAY() END AS normalized_keys_preview
            FROM raw_provider_record r
            WHERE r.import_attempt_id=#{attemptId}
              AND (#{normalizeStatus} IS NULL OR r.normalize_status=#{normalizeStatus})
            ORDER BY r.record_index ASC, r.id ASC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<RawRecordReviewRow> pageRawRecords(
            @Param("attemptId") long attemptId,
            @Param("normalizeStatus") String normalizeStatus,
            @Param("offset") long offset,
            @Param("size") int size);

    @Select("""
            SELECT COUNT(*)
            FROM raw_provider_record r
            WHERE r.import_attempt_id=#{attemptId}
              AND (#{normalizeStatus} IS NULL OR r.normalize_status=#{normalizeStatus})
            """)
    long countRawRecords(
            @Param("attemptId") long attemptId,
            @Param("normalizeStatus") String normalizeStatus);

    @Select("""
            SELECT id,import_attempt_id,record_index,record_locator,provider_record_key,
                   raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at
            FROM raw_provider_record
            WHERE id=#{recordId} AND import_attempt_id=#{attemptId}
            """)
    com.aicostops.ingestion.domain.RawProviderRecord findRawRecordByIdAndAttempt(
            @Param("recordId") long recordId,
            @Param("attemptId") long attemptId);

    @Select("""
            SELECT COUNT(*)
            FROM import_attempt ia
            WHERE ia.id=#{attemptId} AND ia.import_batch_id=#{batchId}
            """)
    int countAttemptOfBatch(
            @Param("attemptId") long attemptId,
            @Param("batchId") long batchId);

    /** Wide flat projection of one ImportBatch plus its latest Attempt review data. */
    record ImportReviewRow(
            long id,
            long organizationId,
            long evidenceId,
            long providerAccountId,
            String expectedProviderCode,
            String sourceType,
            String parserVersion,
            String status,
            Instant periodStart,
            Instant periodEnd,
            long createdByMemberId,
            Instant createdAt,
            Instant updatedAt,
            String evidenceOriginalFilename,
            String providerDisplayName,
            Long latestAttemptId,
            Integer latestAttemptNo,
            String latestAttemptStatus,
            String latestAttemptTrigger,
            Long latestPredecessorAttemptId,
            String latestParserVersion,
            String latestDetectedProviderCode,
            String latestSchemaFingerprint,
            Instant latestStartedAt,
            Instant latestFinishedAt,
            Instant latestCreatedAt,
            Long latestRecordsSeen,
            Long latestRecordsValid,
            Long latestWarningCount,
            Long latestErrorCount,
            String latestErrorCode,
            String latestErrorSummary) {
    }

    /** Raw list projection with bounded key summaries; no payload values. */
    record RawRecordReviewRow(
            long id,
            long importAttemptId,
            long recordIndex,
            String recordLocator,
            String providerRecordKey,
            String normalizeStatus,
            Instant usageStart,
            Instant usageEnd,
            Instant createdAt,
            int rawKeyCount,
            String rawKeysPreview,
            int normalizedKeyCount,
            String normalizedKeysPreview) {
    }
}
