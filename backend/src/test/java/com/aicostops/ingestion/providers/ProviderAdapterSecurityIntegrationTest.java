package com.aicostops.ingestion.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.evidence.application.EvidenceStorageService;
import com.aicostops.ingestion.application.ImportAttemptExecutor;
import com.aicostops.ingestion.application.ImportLeaseService;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.providers.deepseek.DeepSeekProviderAdapter;
import com.aicostops.ingestion.providers.fixtures.ProviderFixtureFactory;
import com.aicostops.ingestion.providers.mimo.MimoProviderAdapter;
import com.aicostops.ingestion.providers.openai.OpenAiProviderAdapter;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioContainerSupport;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fail-closed secret regression: fixture sentinels such as
 * {@code sk-SECRET-SENTINEL-DO-NOT-PERSIST} must never reach any persisted surface
 * (raw payload, normalized payload, issue message, masked raw value) after a real
 * worker pipeline run.
 */
@SpringBootTest
@Tag("integration")
class ProviderAdapterSecurityIntegrationTest extends MinioContainerSupport {

    private static final String SENTINEL = "sk-SECRET-SENTINEL-DO-NOT-PERSIST";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportAttemptExecutor executor;
    @Autowired
    private EvidenceStorageService evidenceStorage;

    private long organizationId;
    private long memberId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("SecurityGate", "security-gate");
        var userId = insertUser("security-gate@example.com");
        memberId = insertMember(organizationId, userId);
        for (var code : List.of("DEEPSEEK", "MIMO", "OPENAI")) {
            jdbc.update("""
                    INSERT INTO provider_account(
                        org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                    VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, organizationId, code, code + " Account");
        }
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void deepSeekSentinelNeverSurvivesPersistence() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,default,"
                        + SENTINEL + ",api_call,0.000002,125\n");
        entries.put("cost-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,main_wallet,1.25,CNY\n");
        var attemptId = runPipeline("DEEPSEEK", DeepSeekProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, "deepseek-secret.zip", "application/zip",
                ProviderFixtureFactory.zip(entries));

        assertThat(attemptStatus(attemptId)).isEqualTo("SUCCEEDED");
        assertNoSentinelInPersistedSurfaces(attemptId);
        var normalized = jdbc.queryForObject("""
                SELECT normalized_payload FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, String.class, attemptId);
        assertThat(normalized).contains("********").contains("credentialHint");
    }

    @Test
    void mimoSentinelNeverSurvivesPersistence() throws Exception {
        var rows = new LinkedHashMap<String, List<List<String>>>();
        rows.put("Model usage detail", Arrays.asList(
                Arrays.asList("Date", "Model", "API Key", "Currency", "Consumed Amount",
                        "Input Hit Amount", "Input Miss Amount", "Output Amount", "Total Tokens",
                        "Input Hit Tokens", "Input Miss Tokens", "Output Tokens",
                        "Total audio duration", "Request Count"),
                Arrays.asList("2026-08-01", "mimo-example", SENTINEL, "CNY",
                        "1.234", "0.5", "0.4", "0.334", "1000", "400", "300", "300", "3600", "10")));
        var attemptId = runPipeline("MIMO", MimoProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, "mimo-secret.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ProviderFixtureFactory.xlsx(rows));

        assertThat(attemptStatus(attemptId)).isEqualTo("SUCCEEDED");
        assertNoSentinelInPersistedSurfaces(attemptId);
        var normalized = jdbc.queryForObject("""
                SELECT normalized_payload FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, String.class, attemptId);
        assertThat(normalized).contains("********").contains("credentialHint");
    }

    @Test
    void sentinelCarryingRowErrorKeepsIssueSurfacesClean() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,default,"
                        + SENTINEL + ",api_call,1,2\n");
        entries.put("cost-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,main_wallet,not-a-number,CNY\n");
        var attemptId = runPipeline("DEEPSEEK", DeepSeekProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, "deepseek-secret-error.zip", "application/zip",
                ProviderFixtureFactory.zip(entries));

        // Row-level ERROR fails the attempt but retains raw rows and issues.
        assertThat(attemptStatus(attemptId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_issue WHERE import_attempt_id=?",
                Integer.class, attemptId)).isGreaterThanOrEqualTo(1);
        assertNoSentinelInPersistedSurfaces(attemptId);
    }

    @Test
    void sentinelAndOversizeInUnknownZipEntryLocatorNeverSurvivePersistence() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,default,"
                        + "key-1,api_call,1,2\n");
        entries.put("cost-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,main_wallet,1.25,CNY\n");
        entries.put(SENTINEL + "-" + "x".repeat(520) + ".txt", "notes");
        var attemptId = runPipeline("DEEPSEEK", DeepSeekProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, "deepseek-entry-locator.zip", "application/zip",
                ProviderFixtureFactory.zip(entries));

        // Unknown archive entry is a WARN: attempt succeeds, issue is persisted.
        assertThat(attemptStatus(attemptId)).isEqualTo("SUCCEEDED");
        var issues = jdbc.queryForList("""
                SELECT record_locator, field_name, message, raw_value_masked
                FROM import_issue WHERE import_attempt_id=?
                """, attemptId);
        assertThat(issues).isNotEmpty();
        for (var row : issues) {
            assertThat(String.valueOf(row.get("record_locator"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("record_locator")).length()).isLessThanOrEqualTo(500);
            assertThat(String.valueOf(row.get("field_name"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("message"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("raw_value_masked"))).doesNotContain(SENTINEL);
        }
    }

    @Test
    void sentinelAndOversizeInUnknownCsvHeaderFieldNameNeverSurvivePersistence() throws Exception {
        var csv = "start_time,end_time,start_time_iso,end_time_iso,"
                + SENTINEL + "-" + "y".repeat(220) + "\n"
                + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,1\n";
        var attemptId = runPipeline("OPENAI", OpenAiProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, "completions_usage_2026-08-01.csv", "text/csv",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Unknown column is a WARN: attempt succeeds, issue is persisted.
        assertThat(attemptStatus(attemptId)).isEqualTo("SUCCEEDED");
        var issues = jdbc.queryForList("""
                SELECT record_locator, field_name, message, raw_value_masked
                FROM import_issue WHERE import_attempt_id=?
                """, attemptId);
        assertThat(issues).isNotEmpty();
        for (var row : issues) {
            assertThat(String.valueOf(row.get("record_locator"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("field_name"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("field_name")).length()).isLessThanOrEqualTo(200);
            assertThat(String.valueOf(row.get("message"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("raw_value_masked"))).doesNotContain(SENTINEL);
        }
    }

    private long runPipeline(
            String providerCode, String parserVersion, ImportSourceType sourceType,
            String filename, String mediaType, byte[] content) {
        var stored = evidenceStorage.store(organizationId, memberId, filename, mediaType,
                new ByteArrayInputStream(content));
        var evidenceId = stored.evidence().id();
        var accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, organizationId, providerCode);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, evidenceId, accountId, providerCode,
                sourceType.name(), parserVersion, memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,?,
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId, parserVersion);
        var lease = leases.claimNext("security-worker-" + providerCode).orElseThrow();
        executor.execute(lease);
        return lease.attemptId();
    }

    private void assertNoSentinelInPersistedSurfaces(long attemptId) {
        var rawRows = jdbc.queryForList(
                "SELECT raw_payload FROM raw_provider_record WHERE import_attempt_id=?", attemptId);
        for (var row : rawRows) {
            assertThat(String.valueOf(row.get("raw_payload"))).doesNotContain(SENTINEL);
        }
        var normalizedRows = jdbc.queryForList(
                "SELECT normalized_payload FROM raw_provider_record WHERE import_attempt_id=?", attemptId);
        for (var row : normalizedRows) {
            assertThat(String.valueOf(row.get("normalized_payload"))).doesNotContain(SENTINEL);
        }
        var issueRows = jdbc.queryForList("""
                SELECT message, raw_value_masked FROM import_issue WHERE import_attempt_id=?
                """, attemptId);
        for (var row : issueRows) {
            assertThat(String.valueOf(row.get("message"))).doesNotContain(SENTINEL);
            assertThat(String.valueOf(row.get("raw_value_masked"))).doesNotContain(SENTINEL);
        }
        var failureRows = jdbc.queryForList("""
                SELECT error_summary FROM import_attempt WHERE id=?
                """, attemptId);
        for (var row : failureRows) {
            assertThat(String.valueOf(row.get("error_summary"))).doesNotContain(SENTINEL);
        }
    }

    private String attemptStatus(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
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
                VALUES (?,'SecurityGate','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
