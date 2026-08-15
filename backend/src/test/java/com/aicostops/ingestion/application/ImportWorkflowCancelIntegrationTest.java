package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class ImportWorkflowCancelIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImportWorkflowCommandService commands;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;
    private long pendingBatchId;
    private long processingBatchId;
    private long parsedBatchId;
    private long failedBatchId;
    private long canceledBatchId;
    private long foreignBatchId;
    private long queuedAttemptId;
    private long runningAttemptId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Cancel", "cancel");
        foreignOrganizationId = insertOrganization("Foreign", "cancel-foreign");
        actorUserId = insertUser("canceler@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("CANCELER", List.of("IMPORT_CANCEL"));
        assign("CANCELER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        pendingBatchId = insertBatch(organizationId, "PENDING");
        queuedAttemptId = insertAttempt(pendingBatchId, 1, "QUEUED", "INITIAL", null);
        processingBatchId = insertBatch(organizationId, "PROCESSING");
        runningAttemptId = insertAttempt(processingBatchId, 1, "RUNNING", "INITIAL", null);
        parsedBatchId = insertBatch(organizationId, "PARSED");
        insertAttempt(parsedBatchId, 1, "SUCCEEDED", "INITIAL", null);
        failedBatchId = insertBatch(organizationId, "FAILED");
        insertAttempt(failedBatchId, 1, "FAILED", "INITIAL", null);
        canceledBatchId = insertBatch(organizationId, "CANCELED");
        insertAttempt(canceledBatchId, 1, "CANCELED", "INITIAL", null);
        foreignBatchId = insertBatch(foreignOrganizationId, "PENDING");
        insertAttempt(foreignBatchId, 1, "QUEUED", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void cancelsPendingQueuedAttemptAndBatch() {
        var detail = commands.cancel(user(), pendingBatchId, "idem-1");

        assertThat(detail.status().name()).isEqualTo("CANCELED");
        assertThat(detail.latestAttempt().status().name()).isEqualTo("CANCELED");
        assertThat(detail.latestAttempt().finishedAt()).isNotNull();
        assertThat(detail.retryable()).isTrue();
        assertThat(detail.cancelable()).isFalse();

        var attempt = jdbc.queryForMap("""
                SELECT status,finished_at,started_at,lease_owner,lease_until,lease_version,
                       records_seen,error_code
                FROM import_attempt WHERE id=?
                """, queuedAttemptId);
        assertThat(attempt.get("status")).isEqualTo("CANCELED");
        assertThat(attempt.get("finished_at")).isNotNull();
        // A queued attempt never started; lineage/counters stay untouched.
        assertThat(attempt.get("started_at")).isNull();
        assertThat(attempt.get("lease_owner")).isNull();
        assertThat(attempt.get("lease_until")).isNull();
        assertThat(((Number) attempt.get("lease_version")).longValue()).isZero();
        assertThat(((Number) attempt.get("records_seen")).longValue()).isZero();
    }

    @Test
    void cancelsProcessingRunningAttemptClearingLeaseButKeepingCountersAndLineage() {
        jdbc.update("""
                UPDATE import_attempt
                SET lease_owner='worker-1', lease_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(6)),
                    lease_version=4, started_at=UTC_TIMESTAMP(6),
                    records_seen=10, records_valid=8, warning_count=1, error_count=1,
                    detected_provider_code='TEST_PROVIDER', schema_fingerprint=?
                WHERE id=?
                """, "f".repeat(64), runningAttemptId);

        var detail = commands.cancel(user(), processingBatchId, "idem-2");
        assertThat(detail.status().name()).isEqualTo("CANCELED");
        assertThat(detail.latestAttempt().status().name()).isEqualTo("CANCELED");

        var attempt = jdbc.queryForMap("""
                SELECT status,finished_at,started_at,lease_owner,lease_until,lease_version,
                       records_seen,records_valid,warning_count,error_count,
                       detected_provider_code,schema_fingerprint
                FROM import_attempt WHERE id=?
                """, runningAttemptId);
        assertThat(attempt.get("status")).isEqualTo("CANCELED");
        assertThat(attempt.get("finished_at")).isNotNull();
        // Original started_at, counters, and inspection lineage are preserved.
        assertThat(attempt.get("started_at")).isNotNull();
        assertThat(attempt.get("lease_owner")).isNull();
        assertThat(attempt.get("lease_until")).isNull();
        assertThat(((Number) attempt.get("lease_version")).longValue()).isEqualTo(4);
        assertThat(((Number) attempt.get("records_seen")).longValue()).isEqualTo(10);
        assertThat(((Number) attempt.get("records_valid")).longValue()).isEqualTo(8);
        assertThat(((Number) attempt.get("warning_count")).longValue()).isEqualTo(1);
        assertThat(((Number) attempt.get("error_count")).longValue()).isEqualTo(1);
        assertThat(attempt.get("detected_provider_code")).isEqualTo("TEST_PROVIDER");
        assertThat(attempt.get("schema_fingerprint")).isEqualTo("f".repeat(64));
    }

    @Test
    void terminalBatchesConflictWithNewKey() {
        for (var batchId : List.of(parsedBatchId, failedBatchId, canceledBatchId)) {
            assertThatThrownBy(() -> commands.cancel(user(), batchId, "idem-term-" + batchId))
                    .isInstanceOf(DomainException.class).satisfies(this::isStateConflict);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id IN (?,?,?)",
                Integer.class, parsedBatchId, failedBatchId, canceledBatchId)).isEqualTo(3);
    }

    @Test
    void cancelWritesExactlyOneAuditEventWithSecretFreeMetadata() {
        commands.cancel(user(), pendingBatchId, "idem-3");

        var audits = jdbc.queryForList("""
                SELECT event_type,subject_type,subject_id,actor_user_id,metadata_json
                FROM audit_event
                """);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("event_type")).isEqualTo("IMPORT_CANCELED");
        assertThat(audits.get(0).get("subject_type")).isEqualTo("IMPORT_BATCH");
        assertThat(((Number) audits.get(0).get("subject_id")).longValue()).isEqualTo(pendingBatchId);
        assertThat(((Number) audits.get(0).get("actor_user_id")).longValue()).isEqualTo(actorUserId);
        var metadata = String.valueOf(audits.get(0).get("metadata_json"));
        assertThat(metadata).contains("attemptId", "previousAttemptStatus", "previousBatchStatus");
        assertThat(metadata).doesNotContain("password", "secret", "token", "api_key");
    }

    @Test
    void sameSuccessfulCancelKeyReplayReturnsStoredResultWithoutSecondAudit() {
        var first = commands.cancel(user(), pendingBatchId, "idem-replay");
        assertThat(first.status().name()).isEqualTo("CANCELED");

        var replay = commands.cancel(user(), pendingBatchId, "idem-replay");
        assertThat(replay.id()).isEqualTo(pendingBatchId);
        assertThat(replay.latestAttempt().status().name()).isEqualTo("CANCELED");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, pendingBatchId)).isEqualTo("CANCELED");
    }

    @Test
    void missingCancelPermissionIsForbiddenAndCrossOrganizationIsNotFound() {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        assertThatThrownBy(() -> commands.cancel(user(), pendingBatchId, "idem-no-perm"))
                .isInstanceOf(DomainException.class).satisfies(this::isForbidden);

        assign("CANCELER", "ORG", organizationId);
        assertThatThrownBy(() -> commands.cancel(user(), foreignBatchId, "idem-foreign"))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
    }

    private void isStateConflict(Throwable throwable) {
        assertDomain(throwable, 409, "STATE_CONFLICT");
    }

    private void isNotFound(Throwable throwable) {
        assertDomain(throwable, 404, "RESOURCE_NOT_FOUND");
    }

    private void isForbidden(Throwable throwable) {
        assertDomain(throwable, 403, "FORBIDDEN");
    }

    private void assertDomain(Throwable throwable, int status, String code) {
        assertThat(throwable).isInstanceOf(DomainException.class);
        var exception = (DomainException) throwable;
        assertThat(exception.status().value()).isEqualTo(status);
        assertThat(exception.code().name()).isEqualTo(code);
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
                VALUES (?,'Canceler','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
