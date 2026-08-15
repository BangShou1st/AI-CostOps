package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Tag("integration")
class ImportWorkflowRetryIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ImportWorkflowCommandService commands;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long evidenceId;
    private long accountId;
    private long failedBatchId;
    private long canceledBatchId;
    private long pendingBatchId;
    private long processingBatchId;
    private long parsedBatchId;
    private long foreignBatchId;
    private long failedAttemptId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Retry", "retry");
        foreignOrganizationId = insertOrganization("Foreign", "retry-foreign");
        actorUserId = insertUser("retrier@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("RETRYER", List.of("IMPORT_RETRY"));
        assign("RETRYER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        failedBatchId = insertBatch(organizationId, "FAILED");
        failedAttemptId = insertAttempt(failedBatchId, 1, "FAILED", "INITIAL", null);
        insertRawRecord(failedAttemptId, "{\"model\":\"x\"}");
        insertIssue(failedAttemptId);
        canceledBatchId = insertBatch(organizationId, "CANCELED");
        insertAttempt(canceledBatchId, 1, "CANCELED", "INITIAL", null);
        pendingBatchId = insertBatch(organizationId, "PENDING");
        insertAttempt(pendingBatchId, 1, "QUEUED", "INITIAL", null);
        processingBatchId = insertBatch(organizationId, "PROCESSING");
        insertAttempt(processingBatchId, 1, "RUNNING", "INITIAL", null);
        parsedBatchId = insertBatch(organizationId, "PARSED");
        insertAttempt(parsedBatchId, 1, "SUCCEEDED", "INITIAL", null);
        foreignBatchId = insertBatch(foreignOrganizationId, "FAILED");
        insertAttempt(foreignBatchId, 1, "FAILED", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void failedBatchRetryCreatesOneManualRetrySuccessorAndSetsBatchPending() {
        var detail = commands.retry(user(), failedBatchId, "idem-1");

        assertThat(detail.status().name()).isEqualTo("PENDING");
        assertThat(detail.retryable()).isFalse();
        assertThat(detail.latestAttempt().attemptNo()).isEqualTo(2);
        assertThat(detail.latestAttempt().triggerType().name()).isEqualTo("MANUAL_RETRY");
        assertThat(detail.latestAttempt().predecessorAttemptId()).isEqualTo(failedAttemptId);
        assertThat(detail.latestAttempt().status().name()).isEqualTo("QUEUED");

        var attemptRow = jdbc.queryForMap("""
                SELECT attempt_no,status,trigger_type,predecessor_attempt_id,lease_owner,lease_until,
                       lease_version,parser_version,records_seen,records_valid,warning_count,error_count,
                       available_at,created_at
                FROM import_attempt WHERE import_batch_id=? AND attempt_no=2
                """, failedBatchId);
        assertThat(attemptRow.get("status")).isEqualTo("QUEUED");
        assertThat(attemptRow.get("trigger_type")).isEqualTo("MANUAL_RETRY");
        assertThat(attemptRow.get("predecessor_attempt_id")).isEqualTo(failedAttemptId);
        assertThat(attemptRow.get("lease_owner")).isNull();
        assertThat(attemptRow.get("lease_until")).isNull();
        assertThat(((Number) attemptRow.get("lease_version")).longValue()).isZero();
        assertThat(attemptRow.get("parser_version")).isEqualTo("test-parser-v1");
        assertThat(attemptRow.get("available_at")).isNotNull();
        assertThat(attemptRow.get("created_at")).isNotNull();

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, failedBatchId)).isEqualTo("PENDING");
    }

    @Test
    void canceledBatchRetryCreatesOneManualRetrySuccessor() {
        var detail = commands.retry(user(), canceledBatchId, "idem-2");

        assertThat(detail.status().name()).isEqualTo("PENDING");
        assertThat(detail.latestAttempt().attemptNo()).isEqualTo(2);
        assertThat(detail.latestAttempt().triggerType().name()).isEqualTo("MANUAL_RETRY");
    }

    @Test
    void activeOrParsedBatchesConflictWithNewKey() {
        for (var batchId : List.of(pendingBatchId, processingBatchId, parsedBatchId)) {
            assertThatThrownBy(() -> commands.retry(user(), batchId, "idem-active-" + batchId))
                    .isInstanceOf(DomainException.class).satisfies(this::isStateConflict);
        }
        // No successor may exist on any of them.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id IN (?,?,?)",
                Integer.class, pendingBatchId, processingBatchId, parsedBatchId)).isEqualTo(3);
    }

    @Test
    void retryKeepsOldAttemptRawRecordsAndIssuesIntact() {
        var before = jdbc.queryForMap("""
                SELECT status,error_code,error_summary,records_seen,created_at
                FROM import_attempt WHERE id=?
                """, failedAttemptId);
        var rawBefore = jdbc.queryForMap("""
                SELECT record_index,record_locator,raw_payload,normalize_status FROM raw_provider_record
                WHERE import_attempt_id=? AND record_index=0
                """, failedAttemptId);
        var issueBefore = jdbc.queryForMap("""
                SELECT issue_code,severity,message,raw_value_masked FROM import_issue
                WHERE import_attempt_id=?
                """, failedAttemptId);

        commands.retry(user(), failedBatchId, "idem-3");

        assertThat(jdbc.queryForMap("SELECT status,error_code,error_summary,records_seen,created_at "
                + "FROM import_attempt WHERE id=?", failedAttemptId)).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT record_index,record_locator,raw_payload,normalize_status "
                + "FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                failedAttemptId)).isEqualTo(rawBefore);
        assertThat(jdbc.queryForMap("SELECT issue_code,severity,message,raw_value_masked "
                + "FROM import_issue WHERE import_attempt_id=?", failedAttemptId)).isEqualTo(issueBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_provider_record", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void retryWritesExactlyOneAuditEventWithSecretFreeMetadata() {
        commands.retry(user(), failedBatchId, "idem-4");

        var audits = jdbc.queryForList("""
                SELECT event_type,subject_type,subject_id,actor_user_id,metadata_json
                FROM audit_event
                """);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("event_type")).isEqualTo("IMPORT_RETRIED");
        assertThat(audits.get(0).get("subject_type")).isEqualTo("IMPORT_BATCH");
        assertThat(((Number) audits.get(0).get("subject_id")).longValue()).isEqualTo(failedBatchId);
        assertThat(((Number) audits.get(0).get("actor_user_id")).longValue()).isEqualTo(actorUserId);
        var metadata = String.valueOf(audits.get(0).get("metadata_json"));
        assertThat(metadata).contains("predecessorAttemptId", "newAttemptId", "previousBatchStatus");
        assertThat(metadata).doesNotContain("password", "secret", "token", "api_key");
    }

    @Test
    void sameSuccessfulKeyReplayReturnsStoredResultWithoutSecondSuccessorOrAudit() {
        var first = commands.retry(user(), failedBatchId, "idem-replay");
        assertThat(first.latestAttempt().attemptNo()).isEqualTo(2);

        var replay = commands.retry(user(), failedBatchId, "idem-replay");
        assertThat(replay.latestAttempt().attemptNo()).isEqualTo(2);
        assertThat(replay.id()).isEqualTo(failedBatchId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?", Integer.class,
                failedBatchId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyReusedForDifferentImportConflicts() {
        commands.retry(user(), failedBatchId, "idem-shared");
        assertThatThrownBy(() -> commands.retry(user(), canceledBatchId, "idem-shared"))
                .isInstanceOf(DomainException.class).satisfies(this::isStateConflict);
        // The second batch must not have been mutated.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?", Integer.class,
                canceledBatchId)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesWithDifferentKeysProduceExactlyOneSuccessorAndOneConflict() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Object> retryCall = () -> {
                start.await();
                try {
                    commands.retry(user(), failedBatchId, "idem-race-" + Thread.currentThread().getId());
                    return "OK";
                } catch (DomainException conflict) {
                    return conflict.code().name();
                }
            };
            var first = pool.submit(retryCall);
            var second = pool.submit(retryCall);
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "STATE_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?", Integer.class,
                failedBatchId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=? AND trigger_type='MANUAL_RETRY'",
                Integer.class, failedBatchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingRetryPermissionIsForbiddenAndCrossOrganizationIsNotFound() {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        assertThatThrownBy(() -> commands.retry(user(), failedBatchId, "idem-no-perm"))
                .isInstanceOf(DomainException.class).satisfies(this::isForbidden);

        assign("RETRYER", "ORG", organizationId);
        assertThatThrownBy(() -> commands.retry(user(), foreignBatchId, "idem-foreign"))
                .isInstanceOf(DomainException.class).satisfies(this::isNotFound);
    }

    @Test
    void overLengthOrBlankKeyFailsBeforeAnyMutation() {
        assertThatThrownBy(() -> commands.retry(user(), failedBatchId, "k".repeat(201)))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
        assertThatThrownBy(() -> commands.retry(user(), failedBatchId, "  "))
                .isInstanceOf(DomainException.class).satisfies(this::isValidationFailed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, failedBatchId)).isEqualTo("FAILED");
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

    private void isValidationFailed(Throwable throwable) {
        assertDomain(throwable, 400, "VALIDATION_FAILED");
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

    private long insertEvidence(long orgId, String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,'pending.csv','text/csv',1,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, actorMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
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
                    NULL,NULL,NULL,NULL,'ERR','summary',0,0,0,0,UTC_TIMESTAMP(6))
                """, batch, attemptNo, status, trigger, predecessor);
        return jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?
                """, Long.class, batch, attemptNo);
    }

    private void insertRawRecord(long attempt, String rawJson) {
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1','k',CAST(? AS JSON),NULL,NULL,NULL,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attempt, rawJson);
    }

    private void insertIssue(long attempt) {
        jdbc.update("""
                INSERT INTO import_issue(
                    import_attempt_id,raw_provider_record_id,severity,issue_code,record_locator,
                    field_name,message,raw_value_masked,created_at)
                VALUES (?,?, 'WARN','FIELD_EMPTY','row=1','model','empty','sk-x',UTC_TIMESTAMP(6))
                """, attempt, jdbc.queryForObject(
                        "SELECT id FROM raw_provider_record WHERE import_attempt_id=?", Long.class, attempt));
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code='RETRYER'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='RETRYER'");
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
                VALUES (?,'Retrier','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
