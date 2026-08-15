package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Audit-write failure must roll back the whole command transaction: no successor
 * Attempt, no Batch state change, no successful idempotency row. The failing
 * audit port is test-only and replaces the production adapter via {@code @Primary}.
 */
@SpringBootTest
@Tag("integration")
class ImportWorkflowAuditRollbackIntegrationTest extends AuthenticationContainersSupport {

    @TestConfiguration
    static class FailingAuditConfiguration {
        @Bean
        @Primary
        ImportWorkflowAuditPort failingAuditPort() {
            return new ImportWorkflowAuditPort() {
                @Override
                public void importRetried(long orgId, long actorUserId, long batchId,
                        long predecessorAttemptId, long newAttemptId, String previousBatchStatus) {
                    throw new IllegalStateException("test audit failure");
                }

                @Override
                public void importCanceled(long orgId, long actorUserId, long batchId,
                        long attemptId, String previousAttemptStatus, String previousBatchStatus) {
                    throw new IllegalStateException("test audit failure");
                }

                @Override
                public void importConfirmed(long orgId, long actorUserId, long batchId,
                        long attemptId, String previousBatchStatus) {
                    throw new IllegalStateException("test audit failure");
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImportWorkflowCommandService commands;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;
    private long failedBatchId;
    private long failedAttemptId;
    private long pendingBatchId;
    private long queuedAttemptId;
    private long readyBatchId;
    private long readyAttemptId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Audit Rollback", "audit-rollback");
        actorUserId = insertUser("audit-rollback@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("WORKFLOW", List.of("IMPORT_RETRY", "IMPORT_CANCEL", "IMPORT_CONFIRM"));
        assign("WORKFLOW", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        failedBatchId = insertBatch(organizationId, "FAILED");
        failedAttemptId = insertAttempt(failedBatchId, 1, "FAILED", "INITIAL", null);
        pendingBatchId = insertBatch(organizationId, "PENDING");
        queuedAttemptId = insertAttempt(pendingBatchId, 1, "QUEUED", "INITIAL", null);
        readyBatchId = insertBatch(organizationId, "READY_FOR_REVIEW");
        readyAttemptId = insertAttempt(readyBatchId, 1, "SUCCEEDED", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void retryAuditFailureRollsBackMutationAndIdempotencyAtomically() {
        assertThatThrownBy(() -> commands.retry(user(), failedBatchId, "idem-audit-fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test audit failure");

        // No MANUAL_RETRY successor committed.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?", Integer.class,
                failedBatchId)).isEqualTo(1);
        // Old Attempt unchanged.
        assertThat(jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?",
                String.class, failedAttemptId)).isEqualTo("FAILED");
        // Batch remains FAILED.
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, failedBatchId)).isEqualTo("FAILED");
        // No successful (or any) idempotency row committed.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }

    @Test
    void cancelAuditFailureRollsBackMutationAndIdempotencyAtomically() {
        assertThatThrownBy(() -> commands.cancel(user(), pendingBatchId, "idem-audit-fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test audit failure");

        // Attempt remains QUEUED; lease/counters lineage unchanged.
        var attempt = jdbc.queryForMap("""
                SELECT status,lease_owner,lease_until,lease_version,records_seen,finished_at
                FROM import_attempt WHERE id=?
                """, queuedAttemptId);
        assertThat(attempt.get("status")).isEqualTo("QUEUED");
        assertThat(attempt.get("lease_owner")).isNull();
        assertThat(((Number) attempt.get("lease_version")).longValue()).isZero();
        assertThat(attempt.get("finished_at")).isNull();
        // Batch remains PENDING.
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, pendingBatchId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }

    @Test
    void confirmAuditFailureRollsBackMutationAndIdempotencyAtomically() {
        assertThatThrownBy(() -> commands.confirm(user(), readyBatchId, "idem-audit-fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test audit failure");

        // The confirm mutation rolled back: batch stays review-ready, no confirmed attempt.
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, readyBatchId)).isEqualTo("READY_FOR_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_attempt_id FROM import_batch WHERE id=?",
                Long.class, readyBatchId)).isNull();
        // The attempt stays SUCCEEDED (nothing touched it).
        assertThat(jdbc.queryForObject("SELECT status FROM import_attempt WHERE id=?",
                String.class, readyAttemptId)).isEqualTo("SUCCEEDED");
        // No idempotency row (provisional or final) and no audit event committed.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
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
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
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
                WHERE r.code='WORKFLOW'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='WORKFLOW'");
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
                VALUES (?,'Audit Rollback','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
