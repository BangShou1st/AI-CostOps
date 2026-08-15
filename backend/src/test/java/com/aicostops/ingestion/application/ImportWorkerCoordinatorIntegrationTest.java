package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.ingestion.infrastructure.ImportWorkerProperties;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
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

@SpringBootTest(properties = "aicostops.ingestion.worker-enabled=true")
@Tag("integration")
class ImportWorkerCoordinatorIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportWorkerCoordinator coordinator;
    @Autowired
    private ImportWorkerProperties properties;
    @Autowired
    private TestProviderAdapter adapter;

    private long organizationId;
    private long memberId;

    @BeforeEach
    void setUp() {
        TestProviderAdapter.reset();
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("Coordinator", "coordinator-test");
        var userId = insertUser("coordinator@example.com");
        memberId = insertMember(organizationId, userId);
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, "TEST_PROVIDER", "Coordinator Test Account");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void pollOnceDispatchesQueuedAttemptThroughTheTaskExecutorAndFinishesIt() throws Exception {
        var batchId = insertQueuedBatch("m".repeat(64));
        TestProviderAdapter.enteredLatch = new CountDownLatch(1);

        coordinator.pollOnce();

        assertThat(TestProviderAdapter.enteredLatch.await(10, TimeUnit.SECONDS)).isTrue();
        awaitAttemptStatus(batchId, "SUCCEEDED", 10_000);
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, batchId)).isEqualTo("READY_FOR_REVIEW");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM import_attempt WHERE import_batch_id=?",
                String.class, batchId)).isEqualTo(coordinator.workerId());
    }

    @Test
    void executorSaturationPreventsClaimingMoreThanConcurrencyAttempts() throws Exception {
        TestProviderAdapter.enteredLatch = new CountDownLatch(2);
        TestProviderAdapter.releaseLatch = new CountDownLatch(1);
        TestProviderAdapter.blockParse = true;
        var firstBatch = insertQueuedBatch("n".repeat(64));
        var secondBatch = insertQueuedBatch("o".repeat(64));

        coordinator.pollOnce();
        coordinator.pollOnce();

        // Both claims are now blocked inside parse, so all local permits are in use.
        assertThat(TestProviderAdapter.enteredLatch.await(10, TimeUnit.SECONDS)).isTrue();
        var thirdBatch = insertQueuedBatch("p".repeat(64));
        coordinator.pollOnce();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM import_attempt WHERE import_batch_id=?", String.class, thirdBatch))
                .isEqualTo("QUEUED");

        TestProviderAdapter.blockParse = false;
        TestProviderAdapter.releaseLatch.countDown();
        awaitAttemptStatus(firstBatch, "SUCCEEDED", 10_000);
        awaitAttemptStatus(secondBatch, "SUCCEEDED", 10_000);
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, thirdBatch)).isEqualTo("PENDING");
    }

    private long insertQueuedBatch(String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, sha256, "org/" + organizationId + "/evidence/" + sha256,
                "coordinator.csv", "text/csv", 1L, memberId);
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
        return batchId;
    }

    private void awaitAttemptStatus(long batchId, String expected, long timeoutMillis) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            var status = jdbc.queryForObject(
                    "SELECT status FROM import_attempt WHERE import_batch_id=?", String.class, batchId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Attempt for batch " + batchId + " never reached " + expected);
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
                VALUES (?,'Coordinator','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
    static class TestWorkerConfiguration {

        @Bean
        TestProviderAdapter testProviderAdapter() {
            return new TestProviderAdapter();
        }
    }

    /** Shared synthetic adapter whose parse blocks on a latch to prove TaskExecutor dispatch. */
    static class TestProviderAdapter implements ProviderAdapter {

        /** Zero-fact whitelisted payload so canonicalization writes nothing for coordinator tests. */
        private static final Map<String, Object> EMPTY_EXPORT_PAYLOAD = Map.of(
                "sourceSchema", "openai.observed-empty-export.v1",
                "recordKind", "EMPTY_USAGE_BUCKET");

        static volatile CountDownLatch enteredLatch = new CountDownLatch(0);
        static volatile CountDownLatch releaseLatch = new CountDownLatch(0);
        static volatile boolean blockParse;

        static void reset() {
            enteredLatch = new CountDownLatch(0);
            releaseLatch = new CountDownLatch(0);
            blockParse = false;
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
            return new InspectionResult("TEST_PROVIDER", "test.file.v1", "coordinator-fingerprint", true, List.of());
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
            sink.accept(new NormalizedProviderRecord(0, "cost.csv:row=1", "record-0",
                    Map.of("row", 0), EMPTY_EXPORT_PAYLOAD, null, null, RawRecordNormalizeStatus.NORMALIZED, List.of()));
        }

        @Override
        public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
            return new NormalizedProviderRecord(record.index(), record.locator(), null,
                    Map.of(), EMPTY_EXPORT_PAYLOAD, null, null, RawRecordNormalizeStatus.NORMALIZED, List.of());
        }
    }
}
