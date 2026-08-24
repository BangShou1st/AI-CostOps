package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ingestion.application.ImportLeaseService.ImportLease;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deterministic worker-vs-cancel races. Every race is started with latches so
 * both sides genuinely overlap on the row locks; the outcome must always be one
 * of the serializable legal results (never a split Attempt/Batch state).
 */
@SpringBootTest
@Tag("integration")
class ImportWorkflowConcurrencyIntegrationTest extends AuthenticationContainersSupport {

    /** Zero-fact whitelisted payload so canonicalization writes nothing for race tests. */
    private static final Map<String, Object> EMPTY_EXPORT_PAYLOAD = Map.of(
            "sourceSchema", "openai.observed-empty-export.v1",
            "recordKind", "EMPTY_USAGE_BUCKET");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImportWorkflowCommandService commands;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportAttemptFinalizationService finalization;
    @Autowired
    private ImportRawPersistenceService persistence;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;
    private long batchId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Race", "race");
        actorUserId = insertUser("racer@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("CANCELER", List.of("IMPORT_CANCEL", "IMPORT_RETRY"));
        assign("CANCELER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        batchId = insertBatch(organizationId, "PROCESSING");
        attemptId = insertAttempt(batchId, 1, "RUNNING", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void cancelVersusWorkerFinalizationOnlyProducesSerializableOutcomes() throws Exception {
        grantActiveLease("worker-1", 1);
        var lease = new ImportLease(attemptId, batchId, "worker-1", 1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var cancel = pool.submit(() -> {
                start.await();
                try {
                    return commands.cancel(user(), batchId, "idem-final");
                } catch (DomainException conflict) {
                    return null;
                }
            });
            var finish = pool.submit(() -> {
                start.await();
                return finalization.completeSuccess(lease);
            });
            start.countDown();

            var cancelResult = cancel.get(30, TimeUnit.SECONDS);
            var finishResult = finish.get(30, TimeUnit.SECONDS);

            var attemptStatus = jdbc.queryForObject(
                    "SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
            var batchStatus = jdbc.queryForObject(
                    "SELECT status FROM import_batch WHERE id=?", String.class, batchId);
            if (cancelResult != null) {
                // Cancel won: attempt and batch both CANCELED; worker fenced.
                assertThat(attemptStatus).isEqualTo("CANCELED");
                assertThat(batchStatus).isEqualTo("CANCELED");
                assertThat(finishResult).isFalse();
            } else {
                // Worker finalization won: attempt SUCCEEDED, batch READY_FOR_REVIEW.
                assertThat(attemptStatus).isEqualTo("SUCCEEDED");
                assertThat(batchStatus).isEqualTo("READY_FOR_REVIEW");
                assertThat(finishResult).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedRetryVersusCancelOnlyProducesLegalBatchAndLatestAttemptStates() throws Exception {
        jdbc.update("UPDATE import_attempt SET status='FAILED', lease_owner=NULL, lease_until=NULL WHERE id=?",
                attemptId);
        jdbc.update("UPDATE import_batch SET status='FAILED' WHERE id=?", batchId);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var retry = pool.submit(() -> {
                start.await();
                return commands.retry(user(), batchId, "idem-retry-race");
            });
            var cancel = pool.submit(() -> {
                start.await();
                return commands.cancel(user(), batchId, "idem-cancel-race");
            });
            start.countDown();

            var retryResult = retry.get(30, TimeUnit.SECONDS);
            var cancelResult = cancel.get(30, TimeUnit.SECONDS);

            assertThat(retryResult).isNotNull();
            assertThat(cancelResult).isNotNull();
            var batchStatus = jdbc.queryForObject(
                    "SELECT status FROM import_batch WHERE id=?", String.class, batchId);
            var latestAttemptStatus = jdbc.queryForObject("""
                    SELECT status FROM import_attempt
                    WHERE import_batch_id=? ORDER BY attempt_no DESC, id DESC LIMIT 1
                    """, String.class, batchId);
            assertThat(batchStatus).isIn("CANCELED", "PENDING");
            if ("CANCELED".equals(batchStatus)) {
                assertThat(latestAttemptStatus).isEqualTo("CANCELED");
            } else {
                assertThat(latestAttemptStatus).isEqualTo("QUEUED");
            }
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM import_attempt
                    WHERE import_batch_id=? AND status IN ('QUEUED','RUNNING')
                    """, Integer.class, batchId)).isLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelFirstFencesWorkerFinalizationDeterministically() {
        grantActiveLease("worker-1", 1);
        var lease = new ImportLease(attemptId, batchId, "worker-1", 1);

        // Cancel commits first, deterministically.
        var detail = commands.cancel(user(), batchId, "idem-cancel-first");
        assertThat(detail.status().name()).isEqualTo("CANCELED");

        // The stale worker's fenced finalization affects zero rows.
        assertThat(finalization.completeSuccess(lease)).isFalse();
        assertThat(finalization.completeFailure(lease, "ERR", "summary")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?",
                String.class, attemptId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, batchId)).isEqualTo("CANCELED");
    }

    @Test
    void cancelFirstFencesRawPersistenceDeterministically() {
        grantActiveLease("worker-1", 1);
        var lease = new ImportLease(attemptId, batchId, "worker-1", 1);
        var record = new NormalizedProviderRecord(
                0, "cost.csv:row=1", "row-key-1",
                Map.of("model", "x"), EMPTY_EXPORT_PAYLOAD,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"),
                RawRecordNormalizeStatus.NORMALIZED, List.of());

        commands.cancel(user(), batchId, "idem-cancel-first");

        var result = persistence.persist(lease, List.of(record), organizationId, "TEST_PROVIDER");
        assertThat(result.leaseLost()).isTrue();
        assertThat(result.recordsPersisted()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                Integer.class, attemptId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, batchId)).isEqualTo("CANCELED");
    }

    @Test
    void cancelFirstBlocksStaleClaimAndLeaseRecoveryDeterministically() {
        // Cancel a PENDING + QUEUED batch first; nothing may re-acquire it.
        var freshBatchId = insertBatch(organizationId, "PENDING");
        insertAttempt(freshBatchId, 1, "QUEUED", "INITIAL", null);
        commands.cancel(user(), freshBatchId, "idem-cancel-first");

        assertThat(leases.claimNext("worker-stale")).isEmpty();
        assertThat(leases.recoverExpiredLease()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM import_attempt WHERE import_batch_id=?",
                String.class, freshBatchId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, freshBatchId)).isEqualTo("CANCELED");
    }

    @Test
    void persistenceBoundaryRedactsSecretShapedLocatorAndRecordKey() {
        grantActiveLease("worker-1", 1);
        var lease = new ImportLease(attemptId, batchId, "worker-1", 1);
        var record = new NormalizedProviderRecord(
                0, "sk-SECRET-SENTINEL-DO-NOT-RETURN:row=1", "credentialId=keyid_fake&sk-SECRET-SENTINEL",
                Map.of("model", "x"), EMPTY_EXPORT_PAYLOAD,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"),
                RawRecordNormalizeStatus.NORMALIZED, List.of());

        var result = persistence.persist(lease, List.of(record), organizationId, "TEST_PROVIDER");
        assertThat(result.leaseLost()).isFalse();
        assertThat(result.recordsPersisted()).isEqualTo(1);

        var locator = jdbc.queryForObject(
                "SELECT record_locator FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                String.class, attemptId);
        var recordKey = jdbc.queryForObject(
                "SELECT provider_record_key FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                String.class, attemptId);
        assertThat(locator).isEqualTo("[REDACTED]:row=1");
        assertThat(recordKey).isEqualTo("credentialId=keyid_fake&[REDACTED]");
        assertThat(locator).doesNotContain("sk-SECRET-SENTINEL");
        assertThat(recordKey).doesNotContain("sk-SECRET-SENTINEL");
    }

    @Test
    void cancelVersusWorkerClaimOnlyProducesSerializableOutcomes() throws Exception {
        // A fresh PENDING + QUEUED batch for the claim race.
        var claimBatchId = insertBatch(organizationId, "PENDING");
        insertAttempt(claimBatchId, 1, "QUEUED", "INITIAL", null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var cancel = pool.submit(() -> {
                start.await();
                try {
                    return commands.cancel(user(), claimBatchId, "idem-claim");
                } catch (DomainException conflict) {
                    return null;
                }
            });
            var claim = pool.submit(() -> {
                start.await();
                return leases.claimNext("worker-race");
            });
            start.countDown();

            var cancelResult = cancel.get(30, TimeUnit.SECONDS);
            var claimed = claim.get(30, TimeUnit.SECONDS);

            // Both serializations end CANCELED: either cancel locked first (claim
            // finds nothing) or claim committed RUNNING+PROCESSING and cancel then
            // legally canceled the running attempt.
            assertThat(attemptStatusOf(claimBatchId)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM import_batch WHERE id=?", String.class, claimBatchId))
                    .isEqualTo("CANCELED");
            assertThat(cancelResult).isNotNull();
            assertThat(cancelResult.status().name()).isEqualTo("CANCELED");
            if (claimed.isPresent()) {
                // Claim won the row lock first, then cancel canceled its lease.
                assertThat(claimed.orElseThrow().attemptId()).isEqualTo(
                        attemptIdOf(claimBatchId));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String attemptStatusOf(long batch) {
        return jdbc.queryForObject(
                "SELECT status FROM import_attempt WHERE import_batch_id=?", String.class, batch);
    }

    private long attemptIdOf(long batch) {
        return jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batch);
    }

    @Test
    void cancelVersusLeaseRecoveryOnlyProducesSerializableOutcomes() throws Exception {
        jdbc.update("""
                UPDATE import_attempt SET lease_owner='worker-expired', lease_version=2,
                    lease_until=TIMESTAMPADD(MINUTE,-5,UTC_TIMESTAMP(6))
                WHERE id=?
                """, attemptId);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var cancel = pool.submit(() -> {
                start.await();
                try {
                    return commands.cancel(user(), batchId, "idem-recover");
                } catch (DomainException conflict) {
                    return null;
                }
            });
            var recover = pool.submit(() -> {
                start.await();
                return leases.recoverExpiredLease();
            });
            start.countDown();

            var cancelResult = cancel.get(30, TimeUnit.SECONDS);
            var recovered = recover.get(30, TimeUnit.SECONDS);

            var attemptStatus = jdbc.queryForObject(
                    "SELECT status FROM import_attempt WHERE id=?", String.class, attemptId);
            if ("CANCELED".equals(attemptStatus)) {
                // Cancel won: recovery finds no expired RUNNING attempt.
                assertThat(recovered).isEmpty();
                assertThat(jdbc.queryForObject(
                        "SELECT status FROM import_batch WHERE id=?", String.class, batchId))
                        .isEqualTo("CANCELED");
            } else {
                // Recovery won first: old attempt FAILED, successor QUEUED, batch
                // PENDING — cancel then legally cancels the successor.
                assertThat(attemptStatus).isEqualTo("FAILED");
                assertThat(recovered).isPresent();
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?",
                        Integer.class, batchId)).isEqualTo(2);
                assertThat(cancelResult).isNotNull();
                assertThat(cancelResult.status().name()).isEqualTo("CANCELED");
                assertThat(jdbc.queryForObject(
                        "SELECT status FROM import_batch WHERE id=?", String.class, batchId))
                        .isEqualTo("CANCELED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelVersusBoundedRawPersistenceOnlyProducesSerializableOutcomes() throws Exception {
        grantActiveLease("worker-1", 1);
        var lease = new ImportLease(attemptId, batchId, "worker-1", 1);
        var record = new NormalizedProviderRecord(
                0, "cost.csv:row=1", "row-key-1",
                Map.of("model", "x"), EMPTY_EXPORT_PAYLOAD,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"),
                RawRecordNormalizeStatus.NORMALIZED, List.of());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var cancel = pool.submit(() -> {
                start.await();
                return commands.cancel(user(), batchId, "idem-persist");
            });
            var persist = pool.submit(() -> {
                start.await();
                return persistence.persist(lease, List.of(record), organizationId, "TEST_PROVIDER");
            });
            start.countDown();

            var cancelResult = cancel.get(30, TimeUnit.SECONDS);
            var persisted = persist.get(30, TimeUnit.SECONDS);

            var batchStatus = jdbc.queryForObject(
                    "SELECT status FROM import_batch WHERE id=?", String.class, batchId);
            var records = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM raw_provider_record WHERE import_attempt_id=?",
                    Integer.class, attemptId);
            if (persisted.leaseLost()) {
                // Cancel won first: fenced persistence inserted nothing.
                assertThat(records).isZero();
                assertThat(cancelResult.status().name()).isEqualTo("CANCELED");
                assertThat(batchStatus).isEqualTo("CANCELED");
            } else {
                // Persistence won: rows committed before cancel; cancel still legal.
                assertThat(records).isEqualTo(1);
                assertThat(cancelResult.status().name()).isEqualTo("CANCELED");
                assertThat(batchStatus).isEqualTo("CANCELED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void grantActiveLease(String workerId, long leaseVersion) {
        jdbc.update("""
                UPDATE import_attempt
                SET lease_owner=?, lease_version=?, lease_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(6))
                WHERE id=?
                """, workerId, leaseVersion, attemptId);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private long batchCounter;

    private long insertBatch(long orgId, String status) {
        var sha = String.format("%064d", ++batchCounter);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,'obj','pending.csv','text/csv',1,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha, actorMemberId);
        var evId = jdbc.queryForObject("SELECT id FROM evidence WHERE org_id=? AND sha256=?",
                Long.class, orgId, sha);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evId, accountId, "TEST_PROVIDER", "FILE_EXPORT", "test-parser-v1", status,
                actorMemberId);
        return jdbc.queryForObject("""
                SELECT id FROM import_batch WHERE org_id=? AND evidence_id=?
                """, Long.class, orgId, evId);
    }

    private long insertAttempt(long batch, int attemptNo, String status, String trigger, Long predecessor) {
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6),NULL,NULL,1,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batch, attemptNo, status, trigger, predecessor);
        return jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?
                """, Long.class, batch, attemptNo);
    }

    private long insertProviderAccount(long orgId, String providerCode, String displayName) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, displayName);
        return jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code=? AND display_name=?
                """, Long.class, orgId, providerCode, displayName);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code='CANCELER'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='CANCELER'");
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
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
                VALUES (?,'Racer','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
