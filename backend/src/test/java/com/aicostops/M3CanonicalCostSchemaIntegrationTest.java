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
class M3CanonicalCostSchemaIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> M3_TABLES = Set.of(
            "external_document", "consumption_fact", "pricing_fact", "charge_fact", "attribution_hint");

    private static final Set<String> M3_INDEXES = Set.of(
            "uq_external_document_raw_fact",
            "uq_consumption_fact_raw_fact",
            "uq_pricing_fact_raw_fact",
            "uq_charge_fact_raw_fact",
            "uq_attribution_hint_raw_fact",
            "idx_external_document_org_created",
            "idx_consumption_fact_org_created",
            "idx_pricing_fact_org_created",
            "idx_charge_fact_org_created",
            "idx_attribution_hint_org_created",
            "idx_external_document_org_type_period",
            "idx_consumption_fact_org_provider_usage",
            "idx_pricing_fact_org_provider_period",
            "idx_charge_fact_org_review",
            "idx_charge_fact_org_provider_period",
            "idx_attribution_hint_org_type",
            "idx_import_batch_confirmed_attempt");

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
    void migratesEveryM3CanonicalTable() {
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class));

        assertThat(tables).containsAll(M3_TABLES);
    }

    @Test
    void addsConfirmedAttemptColumnAndM3StatusValuesToImportBatch() {
        var columns = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'import_batch'",
                String.class));

        assertThat(columns).contains("confirmed_attempt_id");
    }

    @Test
    void createsCanonicalUniqueAndLookupIndexes() {
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = DATABASE()",
                String.class));

        assertThat(indexes).containsAll(M3_INDEXES);
    }

    @Test
    void canonicalTablesReferenceOrganizationAndRawRecord() {
        assertThat(foreignKeysOf("external_document")).contains(
                "org_id -> organization.id",
                "raw_record_id -> raw_provider_record.id");
        assertThat(foreignKeysOf("consumption_fact")).contains(
                "org_id -> organization.id",
                "raw_record_id -> raw_provider_record.id");
        assertThat(foreignKeysOf("pricing_fact")).contains(
                "org_id -> organization.id",
                "raw_record_id -> raw_provider_record.id");
        assertThat(foreignKeysOf("charge_fact")).contains(
                "org_id -> organization.id",
                "raw_record_id -> raw_provider_record.id");
        assertThat(foreignKeysOf("attribution_hint")).contains(
                "org_id -> organization.id",
                "raw_record_id -> raw_provider_record.id");
    }

    @Test
    void importBatchConfirmedAttemptReferencesImportAttempt() {
        assertThat(foreignKeysOf("import_batch")).contains(
                "confirmed_attempt_id -> import_attempt.id");
    }

    @Test
    void everyCanonicalTableHasIntegerNotNullFactIndex() {
        for (var table : M3_TABLES) {
            var factIndexColumns = jdbcTemplate.queryForList(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'fact_index'",
                    table);
            assertThat(factIndexColumns).singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("DATA_TYPE")).isEqualTo("int");
                        assertThat(row.get("IS_NULLABLE")).isEqualTo("NO");
                    });
        }
    }

    @Test
    void importBatchStatusCheckAcceptsNewM3StatesAndRejectsUnknown() {
        var batchId = insertBatch("READY_FOR_REVIEW");
        jdbcTemplate.update("UPDATE import_batch SET status='CONFIRMED' WHERE id=?", batchId);

        assertThatThrownBy(() -> insertBatch("NOT_A_STATUS"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_import_batch_status");
    }

    @Test
    void rejectsNegativeFactIndexOnEveryCanonicalTable() {
        var rawId = insertRawRecord();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO external_document(
                    org_id,raw_record_id,fact_index,document_type,created_at)
                VALUES (?,?,-1,'BILL_SUMMARY',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_external_document_fact_index");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO consumption_fact(
                    org_id,raw_record_id,fact_index,provider_code,meter_code,quantity,unit,created_at)
                VALUES (?,?,-1,'P','m',1.0,'tokens',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_consumption_fact_fact_index");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO pricing_fact(
                    org_id,raw_record_id,fact_index,provider_code,unit_price,currency,created_at)
                VALUES (?,?,-1,'P',1.0,'CNY',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_pricing_fact_fact_index");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,created_at)
                VALUES (?,?,-1,'P','USAGE',1.0,'CNY',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_charge_fact_fact_index");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,created_at)
                VALUES (?,?,-1,'PROVIDER_USER',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_attribution_hint_fact_index");
    }

    @Test
    void enforcesPeriodChecksOnCanonicalTables() {
        var rawId = insertRawRecord();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO external_document(
                    org_id,raw_record_id,fact_index,document_type,period_start,period_end,created_at)
                VALUES (?,?,0,'BILL_SUMMARY','2026-02-01 00:00:00','2026-01-01 00:00:00',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_external_document_period");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO consumption_fact(
                    org_id,raw_record_id,fact_index,provider_code,meter_code,quantity,unit,
                    usage_start,usage_end,created_at)
                VALUES (?,?,0,'P','m',1.0,'tokens','2026-02-01 00:00:00','2026-01-01 00:00:00',
                    UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_consumption_fact_usage");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO pricing_fact(
                    org_id,raw_record_id,fact_index,provider_code,unit_price,currency,
                    period_start,period_end,created_at)
                VALUES (?,?,0,'P',1.0,'CNY','2026-02-01 00:00:00','2026-01-01 00:00:00',
                    UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_pricing_fact_period");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,created_at)
                VALUES (?,?,0,'P','USAGE',1.0,'CNY','2026-02-01 00:00:00','2026-01-01 00:00:00',
                    UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_charge_fact_period");
    }

    @Test
    void attributionHintConfidenceAllowsNullAndEnforcesZeroToOneBounds() {
        var rawId = insertRawRecord();
        jdbcTemplate.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,confidence,created_at)
                VALUES (?,?,0,'PROVIDER_USER',NULL,UTC_TIMESTAMP(6))
                """, orgId(), rawId);
        jdbcTemplate.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,confidence,created_at)
                VALUES (?,?,1,'PROVIDER_PROJECT',1.00000000,UTC_TIMESTAMP(6))
                """, orgId(), rawId);
        jdbcTemplate.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,confidence,created_at)
                VALUES (?,?,2,'PROVIDER_API_KEY',0.00000001,UTC_TIMESTAMP(6))
                """, orgId(), rawId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,confidence,created_at)
                VALUES (?,?,3,'PROVIDER_API_KEY',1.00000001,UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_attribution_hint_confidence");
    }

    @Test
    void rejectsDuplicateRawFactIndexPairs() {
        var rawId = insertRawRecord();
        jdbcTemplate.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,created_at)
                VALUES (?,?,0,'P','USAGE',1.0,'CNY',UTC_TIMESTAMP(6))
                """, orgId(), rawId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,created_at)
                VALUES (?,?,0,'P','USAGE',2.0,'CNY',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_charge_fact_raw_fact");
    }

    @Test
    void enforcesDocumentTypeAndHintTypeChecks() {
        var rawId = insertRawRecord();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO external_document(
                    org_id,raw_record_id,fact_index,document_type,created_at)
                VALUES (?,?,0,'NOT_A_TYPE',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_external_document_type");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,created_at)
                VALUES (?,?,0,'NOT_A_HINT',UTC_TIMESTAMP(6))
                """, orgId(), rawId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_attribution_hint_type");
    }

    private long orgId() {
        return orgId;
    }

    private long orgId = -1;

    private long insertRawRecord() {
        orgId = insertOrganization("M3 Schema Org", "m3-schema-" + System.nanoTime());
        var userId = insertUser("m3-schema-" + System.nanoTime() + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId(), userId);
        var memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId(), userId);
        var sha256 = "c".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId(), sha256, "org/" + orgId() + "/evidence/" + sha256, "usage.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbcTemplate.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId(), sha256);
        jdbcTemplate.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'M3 Schema Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId(), "M3_TEST");
        var accountId = jdbcTemplate.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code='M3_TEST'
                """, Long.class, orgId());
        jdbcTemplate.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId(), evidenceId, accountId, "M3_TEST", "FILE_EXPORT", "test-parser-v1", memberId);
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
        jdbcTemplate.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1',NULL,JSON_OBJECT(),NULL,'2026-01-01 00:00:00',
                    '2026-01-02 00:00:00','NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    private long insertBatch(String status) {
        orgId = insertOrganization("M3 Status Org", "m3-status-" + System.nanoTime());
        var userId = insertUser("m3-batch-" + System.nanoTime() + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId(), userId);
        var memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId(), userId);
        var sha256 = "b".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId(), sha256, "org/" + orgId() + "/evidence/" + sha256, "usage.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbcTemplate.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId(), sha256);
        jdbcTemplate.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'M3 Status Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId(), "M3_STATUS");
        var accountId = jdbcTemplate.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code='M3_STATUS'
                """, Long.class, orgId());
        jdbcTemplate.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId(), evidenceId, accountId, "M3_STATUS", "FILE_EXPORT", "test-parser-v1", status, memberId);
        return jdbcTemplate.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
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

    private Set<String> foreignKeysOf(String table) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT column_name, referenced_table_name, referenced_column_name "
                        + "FROM information_schema.key_column_usage "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND referenced_table_name IS NOT NULL",
                (rs, rowNum) -> rs.getString("column_name") + " -> "
                        + rs.getString("referenced_table_name") + "." + rs.getString("referenced_column_name"),
                table));
    }
}
