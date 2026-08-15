package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.evidence.application.EvidenceStorageService;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioContainerSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "aicostops.ingestion.persistence-batch-size=2")
@Tag("integration")
class ImportAttemptExecutorIntegrationTest extends MinioContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportRawPersistenceService persistence;
    @Autowired
    private ImportAttemptExecutor executor;
    @Autowired
    private EvidenceStorageService evidenceStorage;
    @Autowired
    private TestExecutorAdapter adapter;

    private long organizationId;
    private long memberId;

    @BeforeEach
    void setUp() {
        TestExecutorAdapter.reset();
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("Executor", "executor-test");
        var userId = insertUser("executor@example.com");
        memberId = insertMember(organizationId, userId);
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, "TEST_PROVIDER", "Executor Test Account");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void fiveHundredRecordsPersistInOneAtomicBoundedBatch() {
        var lease = claimedLease("batch-500");
        var records = records(0, 500, RawRecordNormalizeStatus.NORMALIZED, List.of());

        var result = persistence.persist(lease, records);

        assertThat(result.leaseLost()).isFalse();
        assertThat(result.recordsPersisted()).isEqualTo(500);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(500);
        assertThat(jdbc.queryForObject(
                "SELECT records_seen FROM import_attempt WHERE id=?", Long.class, lease.attemptId())).isEqualTo(500);
    }

    @Test
    void fiveHundredAndOneRecordsSplitIntoTwoBoundedTransactions() {
        var lease = claimedLease("batch-501");
        var records = records(0, 501, RawRecordNormalizeStatus.NORMALIZED, List.of());

        var first = persistence.persist(lease, records.subList(0, 500));
        var second = persistence.persist(lease, records.subList(500, 501));

        assertThat(first.recordsPersisted()).isEqualTo(500);
        assertThat(second.recordsPersisted()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(501);
    }

    @Test
    void staleLeasePersistsZeroRowsAndReportsLeaseLost() {
        var lease = claimedLease("batch-stale");
        jdbc.update("""
                UPDATE import_attempt SET lease_owner='usurper', lease_version=lease_version+10 WHERE id=?
                """, lease.attemptId());

        var result = persistence.persist(lease, records(0, 10, RawRecordNormalizeStatus.NORMALIZED, List.of()));

        assertThat(result.leaseLost()).isTrue();
        assertThat(result.recordsPersisted()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT records_seen FROM import_attempt WHERE id=?", Long.class, lease.attemptId())).isZero();
    }

    @Test
    void partialRowsSurviveLaterAttemptFailure() {
        var lease = claimedLease("batch-partial");
        persistence.persist(lease, records(0, 3, RawRecordNormalizeStatus.NORMALIZED, List.of()));
        jdbc.update("""
                UPDATE import_attempt SET status='FAILED', finished_at=UTC_TIMESTAMP(6),
                    error_code='PARSE_ERROR' WHERE id=?
                """, lease.attemptId());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(3);
    }

    @Test
    void secretLikeRawValuesAreRedactedBeforeDatabasePersistence() {
        var lease = claimedLease("batch-redact");
        var sensitive = Map.of(
                "user_id", "u-1",
                "cost", 12.5,
                "api_key", "live-api-key-value",
                "password_hash", "{bcrypt}abc",
                "refresh_token", "tok-123",
                "nested", Map.of("client.secret", "s3cr3t", "safe", "keep-me"));
        var result = persistence.persist(lease, List.of(record(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), sensitive)));

        assertThat(result.recordsPersisted()).isEqualTo(1);
        var stored = jdbc.queryForObject("""
                SELECT raw_payload FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, String.class, lease.attemptId());
        assertThat(stored)
                .contains("\"[REDACTED]\"")
                .doesNotContain("live-api-key-value", "{bcrypt}abc", "tok-123", "s3cr3t");
        assertThat(stored).contains("\"safe\"", "keep-me").contains("\"user_id\"", "u-1");
    }

    @Test
    void secretShapedJsonObjectKeysAreSanitizedBeforePersistence() {
        var lease = claimedLease("batch-key-sanitize");
        var raw = Map.<String, Object>of(
                "sk-SECRET-SENTINEL-DO-NOT-RETURN", "anything",
                "model", "gpt-example",
                "api_key", "live-key-value");
        var normalized = Map.<String, Object>of(
                "api_key=sk-SECRET-SENTINEL-DO-NOT-RETURN", "anything",
                "usage", 12.5);
        var result = persistence.persist(lease, List.of(
                new NormalizedProviderRecord(0, "cost.csv:row=1", "record-0",
                        raw, normalized, null, null, RawRecordNormalizeStatus.NORMALIZED, List.of())));

        assertThat(result.recordsPersisted()).isEqualTo(1);
        var rawStored = jdbc.queryForObject("""
                SELECT raw_payload FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, String.class, lease.attemptId());
        assertThat(rawStored)
                .doesNotContain("SECRET-SENTINEL")
                .doesNotContain("sk-")
                .contains("\"model\"", "gpt-example")
                .contains("\"api_key\"")
                .doesNotContain("live-key-value");
        var normalizedStored = jdbc.queryForObject("""
                SELECT normalized_payload FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, String.class, lease.attemptId());
        assertThat(normalizedStored)
                .doesNotContain("SECRET-SENTINEL")
                .contains("\"usage\"");
    }

    @Test
    void warnAndErrorCountersAreExact() {
        var lease = claimedLease("batch-counters");
        var records = new ArrayList<NormalizedProviderRecord>();
        records.add(record(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of()));
        records.add(record(1, RawRecordNormalizeStatus.WARN, List.of(
                issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN", "row=2")), Map.of()));
        records.add(record(2, RawRecordNormalizeStatus.ERROR, List.of(
                issue(ImportIssueSeverity.ERROR, "MISSING_REQUIRED_COLUMN", "row=3"),
                issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN", "row=3")), Map.of()));

        var result = persistence.persist(lease, records);

        assertThat(result.recordsPersisted()).isEqualTo(3);
        assertThat(result.issuesPersisted()).isEqualTo(3);
        var counters = jdbc.queryForMap("""
                SELECT records_seen,records_valid,warning_count,error_count
                FROM import_attempt WHERE id=?
                """, lease.attemptId());
        assertThat(counters.get("records_seen")).isEqualTo(3L);
        assertThat(counters.get("records_valid")).isEqualTo(1L);
        assertThat(counters.get("warning_count")).isEqualTo(2L);
        assertThat(counters.get("error_count")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_issue WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(3);
    }

    private ImportLeaseService.ImportLease claimedLease(String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, sha256, "org/" + organizationId + "/evidence/" + sha256,
                "executor.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, organizationId, sha256);
        var accountId = jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code='TEST_PROVIDER'
                """, Long.class, organizationId);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, evidenceId, accountId, "TEST_PROVIDER", "FILE_EXPORT",
                "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        return leases.claimNext("executor-worker").orElseThrow();
    }

    private static List<NormalizedProviderRecord> records(
            int startIndex, int count, RawRecordNormalizeStatus status, List<ImportIssueDraft> issues) {
        var records = new ArrayList<NormalizedProviderRecord>(count);
        for (int i = 0; i < count; i++) {
            var index = startIndex + i;
            records.add(new NormalizedProviderRecord(index, "cost.csv:row=" + (index + 1),
                    "record-" + index, Map.of("row", index), Map.of("normalized", index),
                    null, null, status, issues));
        }
        return records;
    }

    private static NormalizedProviderRecord record(
            int index, RawRecordNormalizeStatus status, List<ImportIssueDraft> issues, Map<String, Object> raw) {
        return new NormalizedProviderRecord(index, "cost.csv:row=" + (index + 1), "record-" + index,
                raw, Map.of(), null, null, status, issues);
    }

    private static ImportIssueDraft issue(ImportIssueSeverity severity, String code, String locator) {
        return new ImportIssueDraft(severity, code, locator, "field", "issue message", "masked");
    }

    // ------------------------------------------------------------------
    // Task 11: adapter execution through ImportAttemptExecutor
    // ------------------------------------------------------------------

    @Test
    void warnOnlyImportSucceedsAttemptAndParsesBatch() {
        adapter.records = List.of(
                record(0, RawRecordNormalizeStatus.WARN,
                        List.of(issue(ImportIssueSeverity.WARN, "UNKNOWN_COLUMN", "row=1")), Map.of("row", 0)),
                record(1, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 1)));
        var evidenceId = storeEvidence("warn-only-content");
        var batchId = insertBatchWithAttempt(evidenceId);
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        var counters = attemptCounters(lease.attemptId());
        assertThat(attemptStatus(lease.attemptId())).isEqualTo("SUCCEEDED");
        assertThat(batchStatus(batchId)).isEqualTo("PARSED");
        assertThat(counters.recordsSeen).isEqualTo(2L);
        assertThat(counters.recordsValid).isEqualTo(1L);
        assertThat(counters.warningCount).isEqualTo(1L);
        assertThat(counters.errorCount).isZero();
    }

    @Test
    void incompatibleSchemaFailsAttemptAndBatchWithErrorIssue() {
        adapter.compatible = false;
        adapter.inspectionIssues = List.of(
                issue(ImportIssueSeverity.ERROR, "UNSUPPORTED_SCHEMA", null));
        var evidenceId = storeEvidence("incompatible-content");
        var batchId = insertBatchWithAttempt(evidenceId);
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("FAILED");
        assertThat(errorCodeOf(lease.attemptId())).isEqualTo("SCHEMA_INCOMPATIBLE");
        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_issue WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(1);
    }

    @Test
    void lateParseErrorKeepsPartialRawRowsFromEarlierBoundedFlushes() {
        adapter.records = List.of(
                record(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 0)),
                record(1, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 1)),
                record(2, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 2)),
                record(3, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 3)));
        adapter.failAfterRecords = 3;
        var evidenceId = storeEvidence("late-failure-content");
        var batchId = insertBatchWithAttempt(evidenceId);
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("FAILED");
        assertThat(errorCodeOf(lease.attemptId())).isEqualTo("EXECUTION_FAILED");
        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        // Batch size is 2 in this context: records 0..1 flushed, record 2 pending when parse failed.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(2);
    }

    @Test
    void schemaFingerprintDetectedProviderAndParserVersionArePersistedOnAttempt() {
        adapter.fingerprint = "schema-fingerprint-abc123";
        var evidenceId = storeEvidence("fingerprint-content");
        var batchId = insertBatchWithAttempt(evidenceId);
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("SUCCEEDED");
        var row = jdbc.queryForMap("""
                SELECT parser_version,detected_provider_code,schema_fingerprint
                FROM import_attempt WHERE id=?
                """, lease.attemptId());
        assertThat(row)
                .containsEntry("parser_version", "test-parser-v1")
                .containsEntry("detected_provider_code", "TEST_PROVIDER")
                .containsEntry("schema_fingerprint", "schema-fingerprint-abc123");
    }

    @Test
    void adapterReceivesExactBatchAndEvidenceMetadata() {
        var content = "executor-metadata-content";
        var evidenceId = storeEvidence(content);
        insertBatchWithAttempt(evidenceId);
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("SUCCEEDED");
        var captured = TestExecutorAdapter.capturedInput;
        assertThat(captured).isNotNull();
        assertThat(captured.sourceType()).isEqualTo(ImportSourceType.FILE_EXPORT);
        assertThat(captured.originalFilename()).isEqualTo("executor.csv");
        assertThat(captured.mediaType()).isEqualTo("text/csv");
        assertThat(captured.source().sizeBytes())
                .isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        var sha256 = sha256Hex(content);
        assertThat(captured.source().objectKey())
                .isEqualTo("org/" + organizationId + "/evidence/sha256/"
                        + sha256.substring(0, 2) + "/" + sha256);
    }

    private static String sha256Hex(String content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    @Test
    void staleWorkerCannotFinalizeSuccessOrFailure() {
        adapter.records = List.of(record(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 0)));
        var evidenceId = storeEvidence("stale-finalize-content");
        var batchId = insertBatchWithAttempt(evidenceId);
        var lease = leases.claimNext("executor-worker").orElseThrow();
        // Recovery-style takeover: a new worker owns the attempt now.
        jdbc.update("""
                UPDATE import_attempt SET lease_owner='new-owner', lease_version=lease_version+1 WHERE id=?
                """, lease.attemptId());

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("RUNNING");
        assertThat(batchStatus(batchId)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isZero();
    }

    // ------------------------------------------------------------------
    // Review fix: heartbeat must cover every concurrently active execution
    // ------------------------------------------------------------------

    @Test
    void batchFinalizationFailureRollsBackAttemptAndBatchAtomically() {
        adapter.records = List.of(record(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 0)));
        var batchId = insertBatchWithAttempt(storeEvidence("atomic-finalize-content"));
        var lease = leases.claimNext("executor-worker").orElseThrow();
        // A temporary CHECK forbids the PARSED transition so the Batch finalization
        // statement fails inside the finalization transaction.
        jdbc.update("""
                ALTER TABLE import_batch
                ADD CONSTRAINT chk_test_fail_parsed CHECK (status <> 'PARSED')
                """);
        try {
            assertThatThrownBy(() -> executor.execute(lease))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("chk_test_fail_parsed");

            // The finalization transaction rolled back: no SUCCEEDED + PROCESSING split.
            assertThat(attemptStatus(lease.attemptId())).isEqualTo("RUNNING");
            assertThat(batchStatus(batchId)).isEqualTo("PROCESSING");
        } finally {
            jdbc.update("ALTER TABLE import_batch DROP CHECK chk_test_fail_parsed");
        }
    }

    @Test
    void heartbeatRenewsAllConcurrentlyActiveExecutions() throws Exception {
        TestExecutorAdapter.blockParse = true;
        TestExecutorAdapter.enteredLatch = new CountDownLatch(2);
        TestExecutorAdapter.releaseLatch = new CountDownLatch(1);
        var firstBatch = insertBatchWithAttempt(storeEvidence("hb-1-content"));
        var secondBatch = insertBatchWithAttempt(storeEvidence("hb-2-content"));
        var firstLease = leases.claimNext("worker-hb-1").orElseThrow();
        var secondLease = leases.claimNext("worker-hb-2").orElseThrow();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> executor.execute(firstLease));
            var second = pool.submit(() -> executor.execute(secondLease));
            assertThat(TestExecutorAdapter.enteredLatch.await(10, TimeUnit.SECONDS)).isTrue();

            var beforeFirst = leaseUntil(firstLease.attemptId());
            var beforeSecond = leaseUntil(secondLease.attemptId());

            executor.heartbeatActiveExecutions();

            assertThat(leaseUntil(firstLease.attemptId())).isAfter(beforeFirst);
            assertThat(leaseUntil(secondLease.attemptId())).isAfter(beforeSecond);

            TestExecutorAdapter.blockParse = false;
            TestExecutorAdapter.releaseLatch.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(attemptStatus(firstLease.attemptId())).isEqualTo("SUCCEEDED");
        assertThat(batchStatus(firstBatch)).isEqualTo("PARSED");
        assertThat(batchStatus(secondBatch)).isEqualTo("PARSED");
    }

    @Test
    void oneFinishedExecutionDoesNotClearAnotherActiveHeartbeatEligibility() throws Exception {
        TestExecutorAdapter.blockParse = true;
        TestExecutorAdapter.enteredLatch = new CountDownLatch(1);
        TestExecutorAdapter.releaseLatch = new CountDownLatch(1);
        var blockingBatch = insertBatchWithAttempt(storeEvidence("hb-blocked-content"));
        var blockingLease = leases.claimNext("worker-hb-blocked").orElseThrow();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            var blocking = pool.submit(() -> executor.execute(blockingLease));
            assertThat(TestExecutorAdapter.enteredLatch.await(10, TimeUnit.SECONDS)).isTrue();

            // A second execution starts and finishes while the first is still active.
            TestExecutorAdapter.blockParse = false;
            var finishedBatch = insertBatchWithAttempt(storeEvidence("hb-finished-content"));
            var finishedLease = leases.claimNext("worker-hb-finished").orElseThrow();
            executor.execute(finishedLease);
            assertThat(attemptStatus(finishedLease.attemptId())).isEqualTo("SUCCEEDED");
            assertThat(batchStatus(finishedBatch)).isEqualTo("PARSED");

            var beforeBlocked = leaseUntil(blockingLease.attemptId());
            executor.heartbeatActiveExecutions();
            assertThat(leaseUntil(blockingLease.attemptId())).isAfter(beforeBlocked);

            TestExecutorAdapter.releaseLatch.countDown();
            blocking.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(attemptStatus(blockingLease.attemptId())).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------
    // Review fix: secret fail-closed for issues and error summaries
    // ------------------------------------------------------------------

    @Test
    void secretLiteralsInAdapterIssuesNeverReachTheDatabase() {
        adapter.inspectionIssues = List.of(new ImportIssueDraft(
                ImportIssueSeverity.ERROR, "SECRET_LEAK", "row=1", "credentials",
                "password=supersecret123 token=abc123 api_key=sk-live Authorization: Bearer eyJraw",
                "raw-secret-value"));
        var batchId = insertBatchWithAttempt(storeEvidence("secret-issue-content"));
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("FAILED");
        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        var rows = jdbc.queryForList(
                "SELECT message, raw_value_masked FROM import_issue WHERE import_attempt_id=?",
                lease.attemptId());
        assertThat(rows).isNotEmpty();
        for (var row : rows) {
            assertThat(String.valueOf(row.get("message")))
                    .doesNotContain("supersecret123", "abc123", "sk-live", "eyJraw")
                    .contains("[REDACTED]");
            assertThat(String.valueOf(row.get("raw_value_masked")))
                    .doesNotContain("raw-secret-value")
                    .isEqualTo("[REDACTED]");
        }
    }

    @Test
    void parseExceptionSecretsNeverReachErrorSummary() {
        TestExecutorAdapter.failMessage = "boom password=supersecret123 token=abc123";
        TestExecutorAdapter.failAfterRecords = 1;
        TestExecutorAdapter.records = List.of(
                record(0, RawRecordNormalizeStatus.NORMALIZED, List.of(), Map.of("row", 0)));
        var batchId = insertBatchWithAttempt(storeEvidence("secret-parse-content"));
        var lease = leases.claimNext("executor-worker").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("FAILED");
        var row = jdbc.queryForMap("SELECT error_code, error_summary FROM import_attempt WHERE id=?", lease.attemptId());
        assertThat(row.get("error_code")).isEqualTo("EXECUTION_FAILED");
        assertThat(String.valueOf(row.get("error_summary")))
                .isEqualTo("Provider import execution failed (IllegalStateException).")
                .doesNotContain("supersecret123", "abc123");
        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
    }

    private java.sql.Timestamp leaseUntil(long attemptId) {
        return jdbc.queryForObject(
                "SELECT lease_until FROM import_attempt WHERE id=?", java.sql.Timestamp.class, attemptId);
    }

    private long storeEvidence(String content) {
        var stored = evidenceStorage.store(organizationId, memberId, "executor.csv", "text/csv",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return stored.evidence().id();
    }

    private long insertBatchWithAttempt(long evidenceId) {
        var accountId = jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code='TEST_PROVIDER'
                """, Long.class, organizationId);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, evidenceId, accountId, "TEST_PROVIDER", "FILE_EXPORT",
                "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        return batchId;
    }

    private String attemptStatus(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
    }

    private String errorCodeOf(long attemptId) {
        return jdbc.queryForObject("SELECT error_code FROM import_attempt WHERE id=?", String.class, attemptId);
    }

    private String batchStatus(long batchId) {
        return jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId);
    }

    private Counters attemptCounters(long attemptId) {
        var row = jdbc.queryForMap("""
                SELECT records_seen,records_valid,warning_count,error_count
                FROM import_attempt WHERE id=?
                """, attemptId);
        return new Counters(
                (Long) row.get("records_seen"), (Long) row.get("records_valid"),
                (Long) row.get("warning_count"), (Long) row.get("error_count"));
    }

    private record Counters(long recordsSeen, long recordsValid, long warningCount, long errorCount) {
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,'Executor','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestExecutorAdapterConfiguration {

        @Bean
        TestExecutorAdapter testExecutorAdapter() {
            return new TestExecutorAdapter();
        }
    }

    /** Configurable synthetic adapter for executor behaviour tests. */
    static class TestExecutorAdapter implements ProviderAdapter {

        static volatile boolean compatible = true;
        static volatile String fingerprint = "executor-fingerprint";
        static volatile List<ImportIssueDraft> inspectionIssues = List.of();
        static volatile List<NormalizedProviderRecord> records = List.of();
        static volatile int failAfterRecords = -1;
        static volatile boolean blockParse;
        static volatile CountDownLatch enteredLatch = new CountDownLatch(0);
        static volatile CountDownLatch releaseLatch = new CountDownLatch(0);
        static volatile String failMessage = "simulated late parse failure";
        static volatile ProviderInput capturedInput;

        static void reset() {
            compatible = true;
            fingerprint = "executor-fingerprint";
            inspectionIssues = List.of();
            records = List.of();
            failAfterRecords = -1;
            blockParse = false;
            enteredLatch = new CountDownLatch(0);
            releaseLatch = new CountDownLatch(0);
            failMessage = "simulated late parse failure";
            capturedInput = null;
        }

        @Override
        public String providerCode() {
            return "TEST_PROVIDER";
        }

        @Override
        public String parserVersion() {
            return "test-parser-v1";
        }

        @Override
        public InspectionResult inspect(ProviderInput input) {
            capturedInput = input;
            return new InspectionResult("TEST_PROVIDER", "test.file.v1", fingerprint, compatible, inspectionIssues);
        }

        @Override
        public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
            enteredLatch.countDown();
            if (blockParse) {
                try {
                    releaseLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            var emitted = 0;
            for (var record : records) {
                sink.accept(record);
                emitted++;
                if (failAfterRecords >= 0 && emitted == failAfterRecords) {
                    throw new IllegalStateException(failMessage);
                }
            }
        }

        @Override
        public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
            return new NormalizedProviderRecord(record.index(), record.locator(), null,
                    Map.of(), Map.of(), null, null, RawRecordNormalizeStatus.NORMALIZED, List.of());
        }
    }
}
