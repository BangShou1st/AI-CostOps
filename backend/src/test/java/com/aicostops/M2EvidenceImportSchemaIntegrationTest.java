package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class M2EvidenceImportSchemaIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> M2_TABLES = Set.of(
            "evidence", "import_batch", "import_attempt", "raw_provider_record", "import_issue");

    private static final Set<String> M2_INDEXES = Set.of(
            "uq_evidence_org_sha256",
            "uq_import_batch_identity",
            "uq_import_attempt_batch_no",
            "idx_import_attempt_queue",
            "idx_import_attempt_lease",
            "idx_import_attempt_batch_status",
            "uq_raw_provider_record_attempt_index");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    void migratesEveryM2EvidenceAndImportTable() {
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class));

        assertThat(tables).containsAll(M2_TABLES);
    }

    @Test
    void createsM2DeduplicationUniqueIndexesAndQueueLookupIndexes() {
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE()",
                String.class));

        assertThat(indexes).containsAll(M2_INDEXES);
    }

    @Test
    void evidenceForeignKeysReferenceOrganizationAndUploaderMembership() {
        assertThat(foreignKeysOf("evidence")).contains(
                "org_id -> organization.id",
                "uploaded_by_member_id -> organization_member.id");
    }

    @Test
    void importBatchForeignKeysReferenceOrganizationEvidenceProviderAccountAndCreatorMembership() {
        assertThat(foreignKeysOf("import_batch")).contains(
                "org_id -> organization.id",
                "evidence_id -> evidence.id",
                "provider_account_id -> provider_account.id",
                "created_by_member_id -> organization_member.id");
    }

    @Test
    void importAttemptForeignKeysReferenceItsBatchAndPredecessorAttempt() {
        assertThat(foreignKeysOf("import_attempt")).contains(
                "import_batch_id -> import_batch.id",
                "predecessor_attempt_id -> import_attempt.id");
    }

    @Test
    void rawProviderRecordForeignKeyReferencesItsImportAttempt() {
        assertThat(foreignKeysOf("raw_provider_record")).contains(
                "import_attempt_id -> import_attempt.id");
    }

    @Test
    void importIssueForeignKeysReferenceImportAttemptAndRawProviderRecord() {
        assertThat(foreignKeysOf("import_issue")).contains(
                "import_attempt_id -> import_attempt.id",
                "raw_provider_record_id -> raw_provider_record.id");
    }

    @Test
    void enforcesWellFormedProviderUsageWindowsOnRawRecords() {
        var orgId = insertOrganization("Usage Window", "usage-window");
        var userId = insertUser("usage-window@example.com");
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        var memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
        var sha256 = "e".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, "usage.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbcTemplate.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbcTemplate.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "TEST_PROVIDER", "Usage Window Account");
        var accountId = jdbcTemplate.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code='TEST_PROVIDER'
                """, Long.class, orgId);
        jdbcTemplate.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "TEST_PROVIDER", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbcTemplate.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbcTemplate.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);

        // Legal window: start before end.
        jdbcTemplate.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1',NULL,JSON_OBJECT(),NULL,'2026-01-01 00:00:00','2026-01-02 00:00:00',
                    'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId);
        // Legal window: open start.
        jdbcTemplate.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,1,'cost.csv:row=2',NULL,JSON_OBJECT(),NULL,NULL,'2026-01-02 00:00:00',
                    'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId);

        // Illegal window: start after end must be rejected by the CHECK constraint.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,2,'cost.csv:row=3',NULL,JSON_OBJECT(),NULL,'2026-01-03 00:00:00','2026-01-01 00:00:00',
                    'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_raw_provider_record_usage_window");
    }

    private Set<String> foreignKeysOf(String table) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT column_name, referenced_table_name, referenced_column_name "
                        + "FROM information_schema.key_column_usage "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND referenced_table_name IS NOT NULL",
                (rs, rowNum) -> rs.getString("column_name") + " -> "
                        + rs.getString("referenced_table_name") + "." + rs.getString("referenced_column_name"),
                table));
    }

    private long insertOrganization(String name, String slug) {
        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbcTemplate.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?, 'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Schema User");
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }
}
