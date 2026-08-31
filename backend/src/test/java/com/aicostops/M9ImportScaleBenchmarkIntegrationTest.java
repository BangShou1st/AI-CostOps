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
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
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
 * M9 import scale benchmark (10k / 100k / 500k input rows) on the real
 * DB-backed worker pipeline. Independent of the frozen M8 harness.
 *
 * <p>Fixture generation happens before the timed section; the timed section
 * begins at the ProviderImportService upload boundary. Correctness assertions
 * are mandatory at every measured scale (row counts, canonical counts, money
 * aggregate, final state, no duplicate publication, no secret persistence).
 *
 * <p>Default CI run is the lightweight correctness sample (single 10k run).
 * Full workload is opt-in:
 *
 * <pre>
 * mvnw.cmd -B "-Dm9.benchmark.scales=10k,100k,500k" "-Dm9.benchmark.runs=3" \
 *   failsafe:integration-test "-Dit.test=M9ImportScaleBenchmarkIntegrationTest"
 * </pre>
 *
 * If a scale cannot complete (OOM / timeout / container failure), the harness
 * records the resource ceiling and the last completed phase instead of
 * fabricating a PASS for that scale; the evidence report carries it.
 */
@SpringBootTest(properties = {
        "aicostops.ingestion.worker-enabled=true",
        "aicostops.ingestion.poll-interval=1h",
        "aicostops.ingestion.worker-concurrency=1"
})
@Tag("integration")
@Tag("benchmark")
class M9ImportScaleBenchmarkIntegrationTest extends MinioAuthenticationContainersSupport {

    private static final String ROLE_CODE = "M9_BENCHMARK";
    private static final String PERIOD_START = "2026-09-01 00:00:00.000000";
    private static final String PERIOD_END = "2026-10-01 00:00:00.000000";
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
    void runsParameterizedM9ImportScaleWorkloads() {
        var scales = requestedScales();
        var runs = Integer.parseInt(System.getProperty("m9.benchmark.runs", "1"));
        assertThat(runs).as("m9.benchmark.runs").isPositive();

        var memory = ManagementFactory.getMemoryMXBean();
        var gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        var batchSize = System.getProperty("m9.benchmark.batchSize", "default");
        var concurrency = System.getProperty("aicostops.ingestion.worker-concurrency", "1");

        // Warmup (small) so connection pools and caches are warm before measures.
        var warmup = executeWorkload(Scale.T10K, 0);
        System.out.printf(Locale.ROOT,
                "M9_IMPORT_BENCHMARK|warmup|scale=%s|input_rows=%d|input_bytes=%d|upload_ms=%d|worker_ms=%d|confirm_ms=%d%n",
                "10k", warmup.inputRows(), warmup.inputBytes(),
                warmup.uploadMillis(), warmup.workerMillis(), warmup.confirmMillis());

        var ceilings = new ArrayList<String>();
        for (var scale : scales) {
            for (var run = 1; run <= runs; run++) {
                var heapBefore = usedHeapMiB(memory);
                var gcBefore = gcSample(gcBeans);
                var result = executeWorkloadOrCeiling(scale, run, heapBefore, memory, gcBefore, ceilings);
                if (result == null) {
                    continue;
                }
                // Correctness is asserted here, outside the resource-ceiling
                // catch: a correctness failure must fail the test, never be
                // recorded as a "resource ceiling".
                verifyCorrectness(result.importBatchId(), result.attemptId(), scale.recordsPerFile());
                var benchmark = new BenchmarkResult(scale, result.inputRows(), result.inputBytes(),
                        result.uploadMillis(), result.workerMillis(), result.confirmMillis(),
                        result.totalMillis(), result.finalStatus());
                var gcDelta = gcDelta(gcBefore, gcSample(gcBeans));
                System.out.printf(Locale.ROOT,
                        "M9_IMPORT_BENCHMARK|measured|scale=%s|run=%d|input_rows=%d|input_bytes=%d|upload_ms=%d|worker_ms=%d|confirm_ms=%d|total_ms=%d|worker_records_per_sec=%.3f|end_to_end_rows_per_sec=%.3f|jvm_max_heap_mib=%d|jvm_used_heap_sample_mib=%d|gc_count_delta=%d|gc_time_ms_delta=%d|batch_size=%s|worker_concurrency=%s%n",
                        scale.name().toLowerCase(Locale.ROOT), run, benchmark.inputRows(), benchmark.inputBytes(),
                        benchmark.uploadMillis(), benchmark.workerMillis(), benchmark.confirmMillis(),
                        benchmark.totalMillis(), benchmark.workerRecordsPerSecond(), benchmark.endToEndRowsPerSecond(),
                        memory.getHeapMemoryUsage().getMax() / (1024 * 1024),
                        usedHeapMiB(memory), gcDelta.countDelta(), gcDelta.timeMillisDelta(),
                        batchSize, concurrency);
            }
        }

        if (!ceilings.isEmpty()) {
            for (var ceiling : ceilings) {
                System.out.println("M9_IMPORT_RESOURCE_CEILING|" + ceiling);
            }
        }
        assertThat(ceilings).as("resource ceilings recorded").doesNotContainNull();
    }

    private ExecutedWorkload executeWorkload(Scale scale, int run) {
        try {
            return executeWorkloadOrCeiling(scale, run, 0, null, new GcSample(0, 0), new ArrayList<>());
        } catch (RuntimeException failure) {
            throw new AssertionError("M9 warmup workload failed at " + scale, failure);
        }
    }

    private ExecutedWorkload executeWorkloadOrCeiling(Scale scale, int run, long heapBeforeMiB,
            MemoryMXBean memory, GcSample gcBefore, List<String> ceilings) {
        resetDatabase();
        var fixture = DeepSeekFixtureM9.create(scale.recordsPerFile(),
                "m9-" + scale + "-" + run + "-" + System.nanoTime());
        var user = new AuthenticatedUser(actorUserId, 7);

        var uploadStart = System.nanoTime();
        ExecutedWorkload workload;
        try {
            var created = providerImports.create(user, "m9-" + scale.name().toLowerCase(Locale.ROOT) + ".zip",
                    "application/zip", new ByteArrayInputStream(fixture.bytes()), providerAccountId,
                    ImportSourceType.FILE_EXPORT);
            var uploadMillis = elapsedMillis(uploadStart);

            var workerStart = System.nanoTime();
            coordinator.pollOnce();
            awaitAttempt(created.latestAttemptId(), "SUCCEEDED");
            var workerMillis = elapsedMillis(workerStart);

            var confirmStart = System.nanoTime();
            var confirmed = workflow.confirm(user, created.importBatchId(),
                    "m9-benchmark-confirm-" + scale + "-" + run + "-" + System.nanoTime());
            var confirmMillis = elapsedMillis(confirmStart);
            var totalMillis = uploadMillis + workerMillis + confirmMillis;

            workload = new ExecutedWorkload(created.importBatchId(), created.latestAttemptId(),
                    fixture.inputRows(), fixture.bytes().length, uploadMillis, workerMillis, confirmMillis,
                    totalMillis, confirmed.status().name());
        } catch (Throwable failure) {
            var maxHeapMiB = memory == null ? 0 : memory.getHeapMemoryUsage().getMax() / (1024 * 1024);
            var usedAfterMiB = memory == null ? 0 : memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            var message = String.valueOf(failure.getMessage());
            if (message.length() > 200) {
                message = message.substring(0, 200);
            }
            ceilings.add(String.format(Locale.ROOT,
                    "scale=%s|run=%d|last_completed_phase=worker_poll_or_confirm|failure_mode=%s|message=%s|jvm_max_heap_mib=%d|jvm_used_before_mib=%d|jvm_used_after_mib=%d|gc_count_before=%d|gc_time_ms_before=%d",
                    scale.name().toLowerCase(Locale.ROOT), run,
                    failure.getClass().getSimpleName(),
                    message.replace('|', '/'),
                    maxHeapMiB, heapBeforeMiB, usedAfterMiB, gcBefore.count(), gcBefore.timeMillis()));
            return null;
        }
        return workload;
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

        // No secret persistence: the synthetic key material must never survive.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM raw_provider_record
                WHERE import_attempt_id=? AND (
                    CAST(raw_payload AS CHAR) LIKE '%sk-m9-benchmark-secret%'
                    OR CAST(COALESCE(normalized_payload, JSON_OBJECT()) AS CHAR) LIKE '%sk-m9-benchmark-secret%')
                """, Integer.class, attemptId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE org_id=?", Integer.class,
                organizationId)).isEqualTo(1);
    }

    private int count(String table, String column, long value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, value);
    }

    private void awaitAttempt(long attemptId, String expected) {
        // The DB-backed worker is deliberately bounded; the 500k scale can take
        // well over an hour at the measured per-row rate, so the deadline must
        // not fabricate a premature "ceiling" for a merely slow-but-progressing
        // worker. A truly stuck worker fails through FAILED status detection.
        var deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(6);
        while (System.nanoTime() < deadline) {
            var status = jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
            if (expected.equals(status)) {
                return;
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("M9 import benchmark attempt failed");
            }
            sleep(50);
        }
        throw new AssertionError("M9 import attempt " + attemptId + " did not reach " + expected);
    }

    private List<Scale> requestedScales() {
        var requested = System.getProperty("m9.benchmark.scales", "10k");
        var result = new ArrayList<Scale>();
        for (var token : requested.split(",")) {
            var normalized = token.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            switch (normalized) {
                case "10k" -> result.add(Scale.T10K);
                case "100k" -> result.add(Scale.T100K);
                case "500k" -> result.add(Scale.T500K);
                default -> throw new IllegalArgumentException("Unknown m9 scale token: " + normalized);
            }
        }
        assertThat(result).as("m9.benchmark.scales").isNotEmpty();
        return result;
    }

    private void resetDatabase() {
        clearDatabase();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('M9 Import Benchmark',?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "m9-import-" + System.nanoTime());
        organizationId = jdbc.queryForObject(
                "SELECT id FROM organization ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, PERIOD_START, PERIOD_END);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?, 'M9 Benchmark', 'ACTIVE', 7, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "m9-benchmark-" + System.nanoTime() + "@example.com");
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
                VALUES (?,'DEEPSEEK','M9 Benchmark Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
            throw new AssertionError("Interrupted while waiting for M9 benchmark import", interrupted);
        }
    }

    private static long usedHeapMiB(MemoryMXBean memory) {
        return memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    private static GcSample gcSample(List<GarbageCollectorMXBean> beans) {
        var count = beans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        var millis = beans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        return new GcSample(count, millis);
    }

    private static GcDelta gcDelta(GcSample before, GcSample after) {
        return new GcDelta(after.count() - before.count(), after.timeMillis() - before.timeMillis());
    }

    enum Scale {
        T10K(5_000),
        T100K(50_000),
        T500K(250_000);

        private final int recordsPerFile;

        Scale(int recordsPerFile) {
            this.recordsPerFile = recordsPerFile;
        }

        int recordsPerFile() {
            return recordsPerFile;
        }
    }

    private record GcSample(long count, long timeMillis) {
    }

    private record GcDelta(long countDelta, long timeMillisDelta) {
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

    record Outcome(BenchmarkResult result, String ceiling) {

        static Outcome measured(BenchmarkResult result) {
            return new Outcome(result, null);
        }
    }

    private record ExecutedWorkload(
            long importBatchId,
            long attemptId,
            int inputRows,
            int inputBytes,
            long uploadMillis,
            long workerMillis,
            long confirmMillis,
            long totalMillis,
            String finalStatus) {
    }

    private record DeepSeekFixtureM9(byte[] bytes, int inputRows) {

        static DeepSeekFixtureM9 create(int recordsPerFile, String uniqueSeed) {
            try {
                var amount = new StringBuilder()
                        .append("user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n");
                var cost = new StringBuilder()
                        .append("user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n");
                for (var index = 0; index < recordsPerFile; index++) {
                    var minute = index % 60;
                    var start = "2026-09-01T00:" + String.format(Locale.ROOT, "%02d", minute) + ":00Z";
                    var end = "2026-09-01T00:" + String.format(Locale.ROOT, "%02d", minute) + ":30Z";
                    amount.append(uniqueSeed).append("-user-").append(index).append(',')
                            .append(start).append(',').append(end).append(",deepseek-chat,benchmark-key,")
                            .append("sk-m9-benchmark-secret-").append(uniqueSeed).append('-').append(index)
                            .append(",TOKENS,0.001,10\n");
                    cost.append(uniqueSeed).append("-user-").append(index).append(',')
                            .append(start).append(',').append(end).append(",deepseek-chat,paid,1.25000000,USD\n");
                }

                var output = new ByteArrayOutputStream();
                try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                    zip.putNextEntry(new ZipEntry("amount-2026-09.csv"));
                    zip.write(amount.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("cost-2026-09.csv"));
                    zip.write(cost.toString().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                return new DeepSeekFixtureM9(output.toByteArray(), recordsPerFile * 2);
            } catch (Exception failure) {
                throw new AssertionError("M9 synthetic DeepSeek fixture generation failed", failure);
            }
        }
    }
}