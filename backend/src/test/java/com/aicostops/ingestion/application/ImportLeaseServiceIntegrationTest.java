package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.domain.ImportAttemptStatus;
import com.aicostops.ingestion.infrastructure.ImportWorkerProperties;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class ImportLeaseServiceIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportWorkerProperties properties;

    private long organizationId;
    private long memberId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("Lease", "lease-test");
        var userId = insertUser("lease@example.com");
        memberId = insertMember(organizationId, userId);
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, "TEST_PROVIDER", "Lease Test Account");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void oneQueuedAttemptIsClaimedExactlyOnceByTwoWorkers() {
        var attemptId = insertBatchWithQueuedAttempt("a".repeat(64), 1);
        var batchId = batchOf(attemptId);

        var first = leases.claimNext("worker-a");
        var second = leases.claimNext("worker-b");

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().importBatchId()).isEqualTo(batchId);
        assertThat(second).isEmpty();
        assertThat(statusOf(first.orElseThrow().attemptId())).isEqualTo("RUNNING");
        assertThat(leaseOwnerOf(first.orElseThrow().attemptId())).isEqualTo("worker-a");
        assertThat(batchStatusOf(batchId)).isEqualTo("PROCESSING");    }

    @Test
    void twoQueuedAttemptsAreClaimedByTwoWorkersWithoutBlockingEachOther() throws Exception {
        var firstBatch = batchOf(insertBatchWithQueuedAttempt("a".repeat(64), 1));
        var secondBatch = batchOf(insertBatchWithQueuedAttempt("b".repeat(64), 1));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<Long> claim = () -> {
                start.await();
                return leases.claimNext("worker-" + Thread.currentThread().getId()).orElseThrow().importBatchId();
            };
            var future1 = pool.submit(claim);
            var future2 = pool.submit(claim);
            start.countDown();
            var claimed = List.of(future1.get(), future2.get());

            assertThat(claimed).containsExactlyInAnyOrder(firstBatch, secondBatch);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE status='RUNNING'", Integer.class)).isEqualTo(2);
    }

    @Test
    void heartbeatRenewsTheOwnedLeaseUsingDatabaseTime() {
        var attemptId = insertBatchWithQueuedAttempt("c".repeat(64), 1);
        var lease = leases.claimNext("worker-heartbeat").orElseThrow();
        var before = jdbc.queryForObject("SELECT lease_until FROM import_attempt WHERE id=?", java.sql.Timestamp.class, attemptId);

        var renewed = leases.heartbeat(lease.attemptId(), lease.leaseOwner(), lease.leaseVersion());

        assertThat(renewed).isTrue();
        var after = jdbc.queryForObject("SELECT lease_until FROM import_attempt WHERE id=?", java.sql.Timestamp.class, attemptId);
        assertThat(after.after(before)).isTrue();
    }

    @Test
    void staleOwnerOrVersionCannotHeartbeat() {
        var attemptId = insertBatchWithQueuedAttempt("d".repeat(64), 1);
        var lease = leases.claimNext("worker-owner").orElseThrow();

        assertThat(leases.heartbeat(attemptId, "another-worker", lease.leaseVersion())).isFalse();
        assertThat(leases.heartbeat(attemptId, lease.leaseOwner(), lease.leaseVersion() + 1)).isFalse();
        assertThat(leases.heartbeat(attemptId, "another-worker", lease.leaseVersion() + 1)).isFalse();
    }

    @Test
    void claimHonorsTheAvailabilityTime() {
        var batchId = batchOf(insertBatchWithQueuedAttempt("e".repeat(64), 1));
        jdbc.update("""
                UPDATE import_attempt SET available_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR)
                WHERE import_batch_id=?
                """, batchId);

        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);

        assertThat(leases.claimNext("worker-early")).isEmpty();
        assertThat(statusOf(attemptId)).isEqualTo("QUEUED");
    }

    @Test
    void claimSetsLeaseDurationFromDatabaseClock() {
        var attemptId = insertBatchWithQueuedAttempt("f".repeat(64), 1);
        var lease = leases.claimNext("worker-duration").orElseThrow();
        var expectedMicros = properties.leaseDuration().toNanos() / 1000;

        var leaseUntil = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), lease_until) FROM import_attempt WHERE id=?",
                Long.class, attemptId);
        assertThat(leaseUntil).isBetween(expectedMicros - 5_000_000L, expectedMicros + 5_000_000L);
        assertThat(lease.leaseVersion()).isEqualTo(1L);
    }

    @Test
    void leaseVersionIncrementsOnEachClaim() {
        var attemptId = insertBatchWithQueuedAttempt("g".repeat(64), 1);
        var first = leases.claimNext("worker-v1").orElseThrow();
        var firstVersion = jdbc.queryForObject(
                "SELECT lease_version FROM import_attempt WHERE id=?", Long.class, attemptId);

        // Simulate a recovery re-claim: reset to QUEUED as a recovery worker would.
        jdbc.update("""
                UPDATE import_attempt SET status='QUEUED', lease_owner=NULL, lease_until=NULL
                WHERE id=?
                """, attemptId);
        var second = leases.claimNext("worker-v2").orElseThrow();

        assertThat(firstVersion).isEqualTo(1L);
        assertThat(second.leaseVersion()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT lease_version FROM import_attempt WHERE id=?",
                Long.class, attemptId)).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // Task 9: lease-expiry crash recovery
    // ------------------------------------------------------------------

    @Test
    void expiredLeaseFailsOldAttemptAndQueuesRecoverySuccessorWithLineage() {
        var batchId = batchOf(insertBatchWithQueuedAttempt("h".repeat(64), 1));
        var oldAttemptId = runAttemptWithExpiredLease(batchId, 1);

        var recovery = leases.recoverExpiredLease().orElseThrow();

        var old = jdbc.queryForMap("SELECT * FROM import_attempt WHERE id=?", oldAttemptId);
        assertThat(old.get("status")).isEqualTo("FAILED");
        assertThat(old.get("error_code")).isEqualTo("WORKER_LEASE_EXPIRED");

        var successor = jdbc.queryForMap("SELECT * FROM import_attempt WHERE id=?", recovery.attemptId());
        assertThat(successor.get("status")).isEqualTo("QUEUED");
        assertThat(successor.get("trigger_type")).isEqualTo("LEASE_RECOVERY");
        assertThat(successor.get("attempt_no")).isEqualTo(2);
        assertThat(successor.get("predecessor_attempt_id")).isEqualTo(oldAttemptId);
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, batchId)).isEqualTo("PENDING");
    }

    @Test
    void rawRowsOfFailedAttemptRemainAfterRecovery() {
        var batchId = batchOf(insertBatchWithQueuedAttempt("i".repeat(64), 1));
        var oldAttemptId = runAttemptWithExpiredLease(batchId, 1);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1',NULL,JSON_OBJECT(),NULL,NULL,NULL,'NORMALIZED',UTC_TIMESTAMP(6))
                """, oldAttemptId);

        leases.recoverExpiredLease();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, oldAttemptId)).isEqualTo(1);
    }

    @Test
    void recoveryBudgetExhaustionFailsBatchWithoutSuccessor() {
        var batchId = batchOf(insertBatchWithQueuedAttempt("j".repeat(64), 1));
        var oldAttemptId = runAttemptWithExpiredLease(batchId, 1);
        // Three earlier LEASE_RECOVERY attempts already consumed the whole budget.
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,2,'FAILED','LEASE_RECOVERY',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,UTC_TIMESTAMP(6),'WORKER_LEASE_EXPIRED',NULL,0,0,0,0,UTC_TIMESTAMP(6)),
                       (?,3,'FAILED','LEASE_RECOVERY',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,UTC_TIMESTAMP(6),'WORKER_LEASE_EXPIRED',NULL,0,0,0,0,UTC_TIMESTAMP(6)),
                       (?,4,'FAILED','LEASE_RECOVERY',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,UTC_TIMESTAMP(6),'WORKER_LEASE_EXPIRED',NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId, batchId, batchId);

        var recovery = leases.recoverExpiredLease();

        assertThat(recovery).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?",
                String.class, oldAttemptId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, batchId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=? AND status='QUEUED'",
                Integer.class, batchId)).isZero();
    }

    @Test
    void nonexpiredRunningAttemptIsNotRecovered() {
        var batchId = batchOf(insertBatchWithQueuedAttempt("k".repeat(64), 1));
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);
        jdbc.update("""
                UPDATE import_attempt
                SET status='RUNNING', lease_owner='alive-worker', lease_version=1,
                    lease_until=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 SECOND)
                WHERE id=?
                """, attemptId);
        jdbc.update("UPDATE import_batch SET status='PROCESSING', updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                batchId);

        assertThat(leases.recoverExpiredLease()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?",
                String.class, attemptId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, batchId)).isEqualTo("PROCESSING");
    }

    @Test
    void concurrentRecoveryWorkersCreateExactlyOneSuccessor() throws Exception {
        var batchId = batchOf(insertBatchWithQueuedAttempt("l".repeat(64), 1));
        runAttemptWithExpiredLease(batchId, 1);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<Long> recover = () -> {
                start.await();
                return leases.recoverExpiredLease().map(r -> r.attemptId()).orElse(-1L);
            };
            var future1 = pool.submit(recover);
            var future2 = pool.submit(recover);
            start.countDown();
            var results = List.of(future1.get(), future2.get());

            assertThat(results).containsExactlyInAnyOrder(-1L, results.stream().filter(id -> id > 0).findFirst().orElseThrow());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=? AND trigger_type='LEASE_RECOVERY'",
                Integer.class, batchId)).isEqualTo(1);
    }

    private long batchOf(long attemptId) {
        return jdbc.queryForObject(
                "SELECT import_batch_id FROM import_attempt WHERE id=?", Long.class, attemptId);
    }

    private long runAttemptWithExpiredLease(long batchId, int attemptNo) {
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?",
                Long.class, batchId, attemptNo);
        jdbc.update("""
                UPDATE import_attempt
                SET status='RUNNING', lease_owner='crashed-worker', lease_version=1,
                    lease_until=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND),
                    started_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, attemptId);
        jdbc.update("UPDATE import_batch SET status='PROCESSING', updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                batchId);
        return attemptId;
    }

    /** Inserts one batch with one QUEUED attempt; returns the attempt id. */
    private long insertBatchWithQueuedAttempt(String sha256, int attemptNo) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, sha256, "org/" + organizationId + "/evidence/" + sha256,
                "lease.csv", "text/csv", 1L, memberId);
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
        var batchId = jdbc.queryForObject("""
                SELECT id FROM import_batch WHERE evidence_id=?
                """, Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId, attemptNo);
        return jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id=?
                """, Long.class, batchId);
    }

    private String statusOf(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
    }

    private String leaseOwnerOf(long attemptId) {
        return jdbc.queryForObject("SELECT lease_owner FROM import_attempt WHERE id=?", String.class, attemptId);
    }

    private String batchStatusOf(long batchId) {
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
                VALUES (?,'Lease Worker','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
