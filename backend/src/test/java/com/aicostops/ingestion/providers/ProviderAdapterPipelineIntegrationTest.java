package com.aicostops.ingestion.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.evidence.application.EvidenceStorageService;
import com.aicostops.ingestion.application.ImportAttemptExecutor;
import com.aicostops.ingestion.application.ImportLeaseService;
import com.aicostops.ingestion.application.ProviderAdapterRegistry;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.providers.deepseek.DeepSeekProviderAdapter;
import com.aicostops.ingestion.providers.fixtures.ProviderFixtureFactory;
import com.aicostops.ingestion.providers.glm.GlmProviderAdapter;
import com.aicostops.ingestion.providers.kimi.KimiProviderAdapter;
import com.aicostops.ingestion.providers.mimo.MimoProviderAdapter;
import com.aicostops.ingestion.providers.openai.OpenAiProviderAdapter;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioContainerSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof that every Group 2 provider adapter is a registered Spring bean
 * and executes through the real worker pipeline
 * (Evidence -> inspect -> parse/normalize -> bounded fenced persistence ->
 * PARSED/FAILED) against MySQL + MinIO.
 */
@SpringBootTest
@Tag("integration")
class ProviderAdapterPipelineIntegrationTest extends MinioContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportAttemptExecutor executor;
    @Autowired
    private EvidenceStorageService evidenceStorage;
    @Autowired
    private ProviderAdapterRegistry registry;

    private long organizationId;
    private long memberId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("ProviderPipeline", "provider-pipeline");
        var userId = insertUser("provider-pipeline@example.com");
        memberId = insertMember(organizationId, userId);
        for (var code : List.of("DEEPSEEK", "MIMO", "KIMI", "GLM", "OPENAI")) {
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
    void registryContainsAllFiveProvidersWithFrozenParserVersions() {
        assertThat(registry.findByCode("DEEPSEEK").orElseThrow().parserVersion())
                .isEqualTo(DeepSeekProviderAdapter.PARSER_VERSION);
        assertThat(registry.findByCode("MIMO").orElseThrow().parserVersion())
                .isEqualTo(MimoProviderAdapter.PARSER_VERSION);
        assertThat(registry.findByCode("KIMI").orElseThrow().parserVersion())
                .isEqualTo(KimiProviderAdapter.PARSER_VERSION);
        assertThat(registry.findByCode("GLM").orElseThrow().parserVersion())
                .isEqualTo(GlmProviderAdapter.PARSER_VERSION);
        assertThat(registry.findByCode("OPENAI").orElseThrow().parserVersion())
                .isEqualTo(OpenAiProviderAdapter.PARSER_VERSION);
    }

    @ParameterizedTest
    @MethodSource("compatibleFixtures")
    void providerFixtureParsesAndPersistsEndToEnd(FixtureCase fixture) {
        var evidenceId = storeEvidence(fixture);
        var batchId = insertBatchWithAttempt(fixture);
        var lease = leases.claimNext("pipeline-worker-" + fixture.providerCode).orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("SUCCEEDED");
        assertThat(batchStatus(batchId)).isEqualTo("PARSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isEqualTo(fixture.expectedRecords);
        var attempt = jdbc.queryForMap("""
                SELECT detected_provider_code,schema_fingerprint,parser_version,records_seen
                FROM import_attempt WHERE id=?
                """, lease.attemptId());
        assertThat(attempt.get("detected_provider_code")).isEqualTo(fixture.providerCode);
        assertThat(attempt.get("parser_version")).isEqualTo(fixture.parserVersion);
        assertThat(String.valueOf(attempt.get("schema_fingerprint")))
                .matches("[0-9a-f]{64}");
        assertThat(attempt.get("records_seen")).isEqualTo((long) fixture.expectedRecords);
        var variants = jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(normalized_payload, '$.sourceSchema')) AS source_schema
                FROM raw_provider_record WHERE import_attempt_id=?
                """, lease.attemptId());
        for (var row : variants) {
            assertThat(String.valueOf(row.get("source_schema")))
                    .isEqualTo(fixture.expectedVariant);
        }
    }

    @Test
    void incompatibleDeepSeekZipFailsWithSchemaIncompatibleAndPersistedIssue() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n");
        // cost role missing on purpose
        var content = ProviderFixtureFactory.zip(entries);
        var fixture = new FixtureCase("DEEPSEEK", DeepSeekProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, DeepSeekProviderAdapter.SCHEMA_VARIANT,
                0, "deepseek-incomplete.zip", "application/zip", content);
        var evidenceId = storeEvidence(fixture);
        var batchId = insertBatchWithAttempt(fixture);
        var lease = leases.claimNext("pipeline-worker-incompatible").orElseThrow();

        executor.execute(lease);

        assertThat(attemptStatus(lease.attemptId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM import_attempt WHERE id=?",
                String.class, lease.attemptId())).isEqualTo("SCHEMA_INCOMPATIBLE");
        assertThat(batchStatus(batchId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_issue WHERE import_attempt_id=?",
                Integer.class, lease.attemptId())).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    record FixtureCase(
            String providerCode,
            String parserVersion,
            ImportSourceType sourceType,
            String expectedVariant,
            int expectedRecords,
            String filename,
            String mediaType,
            byte[] content) {
    }

    static Stream<FixtureCase> compatibleFixtures() {
        return Stream.of(
                deepSeekFixture(),
                mimoFixture(),
                kimiFixture(),
                glmFixture(),
                openAiObservedCsvFixture(),
                openAiUsageJsonFixture(),
                openAiCostsJsonFixture());
    }

    private static FixtureCase deepSeekFixture() {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,default,"
                        + "sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,0.000002,125\n");
        entries.put("cost-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,main_wallet,1.25,CNY\n");
        return new FixtureCase("DEEPSEEK", DeepSeekProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, DeepSeekProviderAdapter.SCHEMA_VARIANT,
                2, "deepseek-2026-08.zip", "application/zip", zipOrThrow(entries));
    }

    private static FixtureCase mimoFixture() {
        var rows = new LinkedHashMap<String, List<List<String>>>();
        rows.put("Model usage detail", Arrays.asList(
                Arrays.asList("Date", "Model", "API Key", "Currency", "Consumed Amount",
                        "Input Hit Amount", "Input Miss Amount", "Output Amount", "Total Tokens",
                        "Input Hit Tokens", "Input Miss Tokens", "Output Tokens",
                        "Total audio duration", "Request Count"),
                Arrays.asList("2026-08-01", "mimo-example", "sk-SECRET-SENTINEL-DO-NOT-PERSIST", "CNY",
                        "1.234", "0.5", "0.4", "0.334", "1000", "400", "300", "300", "3600", "10")));
        rows.put("Plugin usage detail", Arrays.asList(
                Arrays.asList("Date", "Plugin", "API Key", "Currency", "Consumed Amount", "Request Count")));
        return new FixtureCase("MIMO", MimoProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, MimoProviderAdapter.SCHEMA_VARIANT,
                1, "mimo-usage.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxOrThrow(rows));
    }

    private static FixtureCase kimiFixture() {
        var rows = new LinkedHashMap<String, List<List<String>>>();
        rows.put("账单汇总", Arrays.asList(
                Arrays.asList("时间范围", "用户ID", "组织ID", "客户主体", "充值账户消耗（元）", "赠送账户消耗（元）"),
                Arrays.asList("2026-08-01 00:00:00 - 2026-08-31 23:59:59",
                        "user-1", "org-1", "某客户主体", "88.50", "11.50")));
        return new FixtureCase("KIMI", KimiProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, KimiProviderAdapter.SCHEMA_VARIANT,
                1, "kimi-billing.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxOrThrow(rows));
    }

    private static FixtureCase glmFixture() {
        var rows = new LinkedHashMap<String, List<List<String>>>();
        rows.put("账单明细", Arrays.asList(
                Arrays.asList("账期(月)", "目录总价", "总消费金额", "信用支付金额", "赠金抵扣金额",
                        "应付金额", "已付款金额", "待付款金额", "结算状态"),
                Arrays.asList("2026-08", "100.00", "90.00", "30.00", "10.00",
                        "50.00", "50.00", "0.00", "已结清")));
        return new FixtureCase("GLM", GlmProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, GlmProviderAdapter.SCHEMA_VARIANT,
                1, "glm-monthly.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxOrThrow(rows));
    }

    private static FixtureCase openAiObservedCsvFixture() {
        return new FixtureCase("OPENAI", OpenAiProviderAdapter.PARSER_VERSION,
                ImportSourceType.FILE_EXPORT, OpenAiProviderAdapter.OBSERVED_EMPTY_EXPORT,
                1, "completions_usage_2026-08-01.csv", "text/csv",
                ("start_time,end_time,start_time_iso,end_time_iso\n"
                        + "1780000000,1780003600,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static FixtureCase openAiUsageJsonFixture() {
        return new FixtureCase("OPENAI", OpenAiProviderAdapter.PARSER_VERSION,
                ImportSourceType.USAGE_API_JSON, OpenAiProviderAdapter.USAGE_JSON,
                1, "usage.json", "application/json",
                classpath("/provider-fixtures/openai/official-usage-completions.json"));
    }

    private static FixtureCase openAiCostsJsonFixture() {
        return new FixtureCase("OPENAI", OpenAiProviderAdapter.PARSER_VERSION,
                ImportSourceType.COSTS_API_JSON, OpenAiProviderAdapter.COSTS_JSON,
                1, "costs.json", "application/json",
                classpath("/provider-fixtures/openai/official-costs.json"));
    }

    private static byte[] zipOrThrow(Map<String, String> entries) {
        try {
            return ProviderFixtureFactory.zip(entries);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] xlsxOrThrow(Map<String, List<List<String>>> rows) {
        try {
            return ProviderFixtureFactory.xlsx(rows);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] classpath(String path) {
        try (var in = ProviderAdapterPipelineIntegrationTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Failed to read fixture " + path, failure);
        }
    }

    // ------------------------------------------------------------------
    // pipeline helpers
    // ------------------------------------------------------------------

    private long storeEvidence(FixtureCase fixture) {
        var stored = evidenceStorage.store(organizationId, memberId, fixture.filename, fixture.mediaType,
                new ByteArrayInputStream(fixture.content));
        return stored.evidence().id();
    }

    private long insertBatchWithAttempt(FixtureCase fixture) {
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND original_filename=?",
                Long.class, organizationId, fixture.filename);
        var accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, organizationId, fixture.providerCode);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, evidenceId, accountId, fixture.providerCode,
                fixture.sourceType.name(), fixture.parserVersion, memberId);
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
                """, batchId, fixture.parserVersion);
        return batchId;
    }

    private String attemptStatus(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
    }

    private String batchStatus(long batchId) {
        return jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId);
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
                VALUES (?,'Pipeline','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
