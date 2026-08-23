package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.application.ImportWorkflowCommandService;
import com.aicostops.ingestion.application.ProviderImportService;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioAuthenticationContainersSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reproducible, real-container import scale benchmark.
 *
 * <p>Fixture construction is intentionally outside the timed section. The timed
 * section starts at the real ProviderImportService upload boundary, includes
 * MySQL/MinIO persistence and the DB-backed worker pipeline, and records confirm
 * separately. The default is one small correctness-safe run; manual sampling
 * uses the documented scale/run system properties.
 */
@SpringBootTest(properties = {
        "aicostops.ingestion.worker-enabled=true",
        "aicostops.ingestion.poll-interval=1h",
        "aicostops.ingestion.worker-concurrency=1"
})
@Tag("integration")
@Tag("benchmark")
class M8ImportScaleBenchmarkIntegrationTest extends MinioAuthenticationContainersSupport {

    private static final String ROLE_CODE = "M8_BENCHMARK";
    private static final String PERIOD_START = "2026-08-01 00:00:00.000000";
    private static final String PERIOD_END = "2026-09-01 00:00:00.000000";
    private static final BigDecimal COST_PER_ROW = new BigDecimal("1.25000000");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ProviderImportService providerImports;
    @Autowired
    private ImportWorkflowCommandService workflow;
    @Autowired
    private com.aicostops.ingestion.application.ImportWorkerCoordinator coordinator;

    private long organizationId;
    private long actorUserId;
    private long providerAccountId;

    @BeforeEach
    void setUp() {
        resetDatabase();
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
    }

    @Test
    void runsWarmupAndParameterizedImportScaleWorkloads() {
        var warmup = runWorkload(Scale.SMALL, 0);
        System.out.printf(Locale.ROOT,
                "M8_BENCHMARK|warmup|scale=%s|input_rows=%d|input_bytes=%d|upload_ms=%d|worker_ms=%d|confirm_ms=%d%n",
                warmup.scale().name().toLowerCase(Locale.ROOT), warmup.inputRows(), warmup.inputBytes(),
                warmup.uploadMillis(), warmup.workerMillis(), warmup.confirmMillis());

        var scales = requestedScales();
        var runs = Integer.parseInt(System.getProperty("m8.benchmark.runs", "1"));
        assertThat(runs).as("m8.benchmark.runs").isPositive();
        for (var scale : scales) {
            for (var run = 1; run <= runs; run++) {
                var result = runWorkload(scale, run);
                System.out.printf(Locale.ROOT,
                        "M8_BENCHMARK|measured|scale=%s|run=%d|input_rows=%d|input_bytes=%d|upload_ms=%d|worker_ms=%d|confirm_ms=%d|total_ms=%d|worker_records_per_sec=%.3f|end_to_end_rows_per_sec=%.3f%n",
                        scale.name().toLowerCase(Locale.ROOT), run, result.inputRows(), result.inputBytes(),
                        result.uploadMillis(), result.workerMillis(), result.confirmMillis(), result.totalMillis(),
                        result.workerRecordsPerSecond(), result.endToEndRowsPerSecond());
            }
        }
    }

    private BenchmarkResult runWorkload(Scale scale, int run) {
        resetDatabase();
        var fixture = DeepSeekFixture.create(scale.recordsPerFile(), "m8-" + scale + "-" + run + "-" + System.nanoTime());
        var user = new AuthenticatedUser(actorUserId, 7);

        var uploadStart = System.nanoTime();
        var created = providerImports.create(user, "m8-" + scale.name().toLowerCase(Locale.ROOT) + ".zip",
                "application/zip", new ByteArrayInputStream(fixture.bytes()), providerAccountId,
                ImportSourceType.FILE_EXPORT);
        var uploadMillis = elapsedMillis(uploadStart);

        var workerStart = System.nanoTime();
        coordinator.pollOnce();
        awaitAttempt(created.latestAttemptId(), "SUCCEEDED");
        var workerMillis = elapsedMillis(workerStart);

        var confirmStart = System.nanoTime();
        var confirmed = workflow.confirm(user, created.importBatchId(),
                "m8-benchmark-confirm-" + scale.name() + "-" + run + "-" + System.nanoTime());
        var confirmMillis = elapsedMillis(confirmStart);
        var totalMillis = uploadMillis + workerMillis + confirmMillis;

        verifyCorrectness(created.importBatchId(), created.latestAttemptId(), scale.recordsPerFile());
        return new BenchmarkResult(scale, fixture.inputRows(), fixture.bytes().length, uploadMillis, workerMillis,
                confirmMillis, totalMillis, confirmed.status().name());
    }

    private void verifyCorrectness(long batchId, long attemptId, int recordsPerFile) {
        var expectedRaw = recordsPerFile * 2L;
        var expectedCharges = recordsPerFile;
        var expectedHints = recordsPerFile * 3L;

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?", String.class, batchId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmed_attempt_id FROM import_batch WHERE id=?", Long.class, batchId))
                .isEqualTo(attemptId);
        assertThat(jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT records_seen FROM import_attempt WHERE id=?", Long.class, attemptId))
                .isEqualTo(expectedRaw);
        assertThat(jdbc.queryForObject("SELECT records_valid FROM import_attempt WHERE id=?", Long.class, attemptId))
                .isEqualTo(expectedRaw);
        assertThat(jdbc.queryForObject("SELECT error_count FROM import_attempt WHERE id=?", Long.class, attemptId))
                .isZero();

        assertThat(count("raw_provider_record", "import_attempt_id", attemptId)).isEqualTo(expectedRaw);
        assertThat(count("external_document", "org_id", organizationId)).isZero();
        assertThat(count("consumption_fact", "org_id", organizationId)).isZero();
        assertThat(count("pricing_fact", "org_id", organizationId)).isZero();
        assertThat(count("charge_fact", "org_id", organizationId)).isEqualTo(expectedCharges);
        assertThat(count("attribution_hint", "org_id", organizationId)).isEqualTo(expectedHints);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_issue WHERE import_attempt_id=?",
                Integer.class, attemptId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duplicate_candidate WHERE org_id=?",
                Integer.class, organizationId)).isZero();

        var expectedAmount = COST_PER_ROW.multiply(BigDecimal.valueOf(recordsPerFile));
        var actualAmount = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM charge_fact WHERE org_id=?",
                BigDecimal.class, organizationId);
        assertThat(actualAmount).isEqualByComparingTo(expectedAmount);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT currency) FROM charge_fact WHERE org_id=?",
                Integer.class, organizationId)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM raw_provider_record
                WHERE import_attempt_id=? AND (
                    CAST(raw_payload AS CHAR) LIKE '%sk-m8-benchmark-secret%'
                    OR CAST(COALESCE(normalized_payload, JSON_OBJECT()) AS CHAR) LIKE '%sk-m8-benchmark-secret%')
                """, Integer.class, attemptId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?", Integer.class,
                organizationId)).isEqualTo(1);
    }

    private int count(String table, String column, long value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, value);
    }

    private void awaitAttempt(long attemptId, String expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            var status = jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                var summary = jdbc.queryForObject("SELECT error_code FROM import_attempt WHERE id=?",
                        String.class, attemptId);
                throw new AssertionError("Import benchmark failed: " + summary);
            }
            sleep(25);
        }
        throw new AssertionError("Import attempt " + attemptId + " did not reach " + expected);
    }

    private List<Scale> requestedScales() {
        var requested = System.getProperty("m8.benchmark.scales", "small");
        var result = new ArrayList<Scale>();
        for (var token : requested.split(",")) {
            var normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                result.add(Scale.valueOf(normalized.toUpperCase(Locale.ROOT)));
            }
        }
        assertThat(result).as("m8.benchmark.scales").isNotEmpty();
        return result;
    }

    private void resetDatabase() {
        clearDatabase();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('M8 Import Benchmark',?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "m8-import-" + System.nanoTime());
        organizationId = jdbc.queryForObject(
                "SELECT id FROM organization ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, PERIOD_START, PERIOD_END);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?, 'M8 Benchmark', 'ACTIVE', 7, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "m8-benchmark-" + System.nanoTime() + "@example.com");
        actorUserId = jdbc.queryForObject("SELECT id FROM app_user ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, actorUserId);
        var actorMemberId = jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class,
                organizationId, actorUserId);
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,'DEEPSEEK','M8 Benchmark Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId);
        providerAccountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=?", Long.class, organizationId);
        createBenchmarkRole(actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private void clearDatabase() {
        if (jdbc == null) {
            return;
        }
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteBenchmarkRole();
    }

    private void createBenchmarkRole(long actorMemberId) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", ROLE_CODE, ROLE_CODE);
        for (var permission : List.of("EVIDENCE_UPLOAD_PROVIDER", "IMPORT_CONFIRM")) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, ROLE_CODE, permission);
        }
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,'ORG',?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, organizationId, ROLE_CODE);
    }

    private void deleteBenchmarkRole() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id WHERE r.code=?
                """, ROLE_CODE);
        jdbc.update("DELETE FROM `role` WHERE code=?", ROLE_CODE);
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for benchmark import", interrupted);
        }
    }

    enum Scale {
        SMALL(32),
        MEDIUM(128),
        LARGE(512);

        private final int recordsPerFile;

        Scale(int recordsPerFile) {
            this.recordsPerFile = recordsPerFile;
        }

        int recordsPerFile() {
            return recordsPerFile;
        }
    }

    private record BenchmarkResult(
            Scale scale,
            int inputRows,
            int inputBytes,
            long uploadMillis,
            long workerMillis,
            long confirmMillis,
            long totalMillis,
            String finalStatus) {

        double workerRecordsPerSecond() {
            return workerMillis == 0 ? Double.POSITIVE_INFINITY : inputRows * 1000.0 / workerMillis;
        }

        double endToEndRowsPerSecond() {
            return totalMillis == 0 ? Double.POSITIVE_INFINITY : inputRows * 1000.0 / totalMillis;
        }
    }

    private record DeepSeekFixture(byte[] bytes, int inputRows) {

        static DeepSeekFixture create(int recordsPerFile, String uniqueSeed) {
            try {
                var amount = new StringBuilder()
                        .append("user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n");
                var cost = new StringBuilder()
                        .append("user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n");
                for (var index = 0; index < recordsPerFile; index++) {
                    var start = "2026-08-01T00:" + String.format(Locale.ROOT, "%02d", index % 60) + ":00Z";
                    var end = "2026-08-01T00:" + String.format(Locale.ROOT, "%02d", index % 60) + ":30Z";
                    amount.append(uniqueSeed).append("-user-").append(index).append(',')
                            .append(start).append(',').append(end).append(",deepseek-chat,benchmark-key,")
                            .append("sk-m8-benchmark-secret-").append(uniqueSeed).append('-').append(index)
                            .append(",TOKENS,0.001,10\n");
                    cost.append(uniqueSeed).append("-user-").append(index).append(',')
                            .append(start).append(',').append(end).append(",deepseek-chat,paid,1.25000000,USD\n");
                }

                var output = new ByteArrayOutputStream();
                try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                    zip.putNextEntry(new ZipEntry("amount-2026-08.csv"));
                    zip.write(amount.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("cost-2026-08.csv"));
                    zip.write(cost.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                return new DeepSeekFixture(output.toByteArray(), recordsPerFile * 2);
            } catch (Exception failure) {
                throw new AssertionError("Synthetic DeepSeek fixture generation failed", failure);
            }
        }
    }
}
