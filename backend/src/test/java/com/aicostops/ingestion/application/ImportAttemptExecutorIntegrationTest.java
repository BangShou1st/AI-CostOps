package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class ImportAttemptExecutorIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportRawPersistenceService persistence;

    private long organizationId;
    private long memberId;

    @BeforeEach
    void setUp() {
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
                "api_key", "sk-live-secret-value",
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
                .doesNotContain("sk-live-secret-value", "{bcrypt}abc", "tok-123", "s3cr3t");
        assertThat(stored).contains("\"safe\"", "keep-me").contains("\"user_id\"", "u-1");
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
}
