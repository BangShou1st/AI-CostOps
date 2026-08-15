package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Import Confirm semantics: READY_FOR_REVIEW + SUCCEEDED + zero errors confirm
 * in one transaction; CONFIRMED re-confirm of the same attempt is idempotent
 * success under a new key; anything else conflicts; cross-organization is 404.
 */
@SpringBootTest
@Tag("integration")
class ImportConfirmIntegrationTest extends AuthenticationContainersSupport {

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
    private long readyBatchId;
    private long readyAttemptId;
    private long readyWithErrorsBatchId;
    private long confirmedBatchId;
    private long confirmedAttemptId;
    private long confirmedMismatchBatchId;
    private long parsedBatchId;
    private long failedBatchId;
    private long foreignReadyBatchId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Confirm", "confirm");
        foreignOrganizationId = insertOrganization("Foreign", "confirm-foreign");
        actorUserId = insertUser("confirmer@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        assign("FINANCE_REVIEWER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        readyBatchId = insertBatch(organizationId, "READY_FOR_REVIEW");
        readyAttemptId = insertAttempt(readyBatchId, 1, "SUCCEEDED", "INITIAL", null, 0);
        readyWithErrorsBatchId = insertBatch(organizationId, "READY_FOR_REVIEW");
        insertAttempt(readyWithErrorsBatchId, 1, "SUCCEEDED", "INITIAL", null, 2);
        confirmedBatchId = insertBatch(organizationId, "CONFIRMED");
        confirmedAttemptId = insertAttempt(confirmedBatchId, 1, "SUCCEEDED", "INITIAL", null, 0);
        jdbc.update("""
                UPDATE import_batch SET confirmed_attempt_id=?, updated_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, confirmedAttemptId, confirmedBatchId);
        confirmedMismatchBatchId = insertBatch(organizationId, "CONFIRMED");
        insertAttempt(confirmedMismatchBatchId, 1, "SUCCEEDED", "INITIAL", null, 0);
        insertAttempt(confirmedMismatchBatchId, 2, "SUCCEEDED", "INITIAL", confirmedAttemptId, 0);
        jdbc.update("""
                UPDATE import_batch SET confirmed_attempt_id=?, updated_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, confirmedAttemptId, confirmedMismatchBatchId);
        parsedBatchId = insertBatch(organizationId, "PARSED");
        insertAttempt(parsedBatchId, 1, "SUCCEEDED", "INITIAL", null, 0);
        failedBatchId = insertBatch(organizationId, "FAILED");
        insertAttempt(failedBatchId, 1, "FAILED", "INITIAL", null, 1);
        foreignReadyBatchId = insertBatch(foreignOrganizationId, "READY_FOR_REVIEW");
        insertAttempt(foreignReadyBatchId, 1, "SUCCEEDED", "INITIAL", null, 0);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void firstConfirmMarksBatchConfirmedWithAttemptAndOneAuditEvent() {
        var detail = commands.confirm(user(), readyBatchId, "idem-confirm-1");

        assertThat(detail.status().name()).isEqualTo("CONFIRMED");
        assertThat(detail.confirmedAttemptId()).isEqualTo(readyAttemptId);
        assertThat(detail.retryable()).isFalse();
        assertThat(detail.cancelable()).isFalse();

        var row = jdbc.queryForMap("SELECT status, confirmed_attempt_id FROM import_batch WHERE id=?",
                readyBatchId);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) row.get("confirmed_attempt_id")).longValue()).isEqualTo(readyAttemptId);

        var audits = jdbc.queryForList("""
                SELECT event_type, subject_type, subject_id, actor_user_id, metadata_json
                FROM audit_event
                """);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("event_type")).isEqualTo("IMPORT_CONFIRMED");
        assertThat(audits.get(0).get("subject_type")).isEqualTo("IMPORT_BATCH");
        assertThat(((Number) audits.get(0).get("subject_id")).longValue()).isEqualTo(readyBatchId);
        assertThat(((Number) audits.get(0).get("actor_user_id")).longValue()).isEqualTo(actorUserId);
        var metadata = String.valueOf(audits.get(0).get("metadata_json"));
        assertThat(metadata).contains("attemptId", "previousBatchStatus")
                .contains("READY_FOR_REVIEW");
        assertThat(metadata).doesNotContain("password", "secret", "token", "api_key");
    }

    @Test
    void sameKeyReplayReturnsStoredResponseWithoutSecondTransition() {
        commands.confirm(user(), readyBatchId, "idem-replay");

        var replay = commands.confirm(user(), readyBatchId, "idem-replay");

        assertThat(replay.status().name()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM import_batch WHERE id=?", String.class, readyBatchId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
    }

    @Test
    void semanticReconfirmWithNewKeySucceedsWithoutSecondAuditOrProvisionalRow() {
        commands.confirm(user(), readyBatchId, "idem-semantic-1");

        var reconfirm = commands.confirm(user(), readyBatchId, "idem-semantic-2");

        assertThat(reconfirm.status().name()).isEqualTo("CONFIRMED");
        assertThat(reconfirm.confirmedAttemptId()).isEqualTo(readyAttemptId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_idempotency WHERE response_status=0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(2);
    }

    @Test
    void confirmedBatchWithDifferentLatestAttemptConflicts() {
        assertThatThrownBy(() -> commands.confirm(user(), confirmedMismatchBatchId, "idem-mismatch"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isStateConflict);
    }

    @Test
    void readyForReviewWithBlockingErrorsConflicts() {
        assertThatThrownBy(() -> commands.confirm(user(), readyWithErrorsBatchId, "idem-errors"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isStateConflict);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM import_batch WHERE id=?", String.class, readyWithErrorsBatchId))
                .isEqualTo("READY_FOR_REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }

    @Test
    void nonReviewReadyStatusesConflict() {
        assertThatThrownBy(() -> commands.confirm(user(), parsedBatchId, "idem-parsed"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isStateConflict);
        assertThatThrownBy(() -> commands.confirm(user(), failedBatchId, "idem-failed"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isStateConflict);
    }

    @Test
    void foreignOrganizationBatchIsNotFound() {
        assertThatThrownBy(() -> commands.confirm(user(), foreignReadyBatchId, "idem-foreign"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isNotFound);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
    }

    @Test
    void missingPermissionIsForbiddenBeforeResourceLookup() {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);

        assertThatThrownBy(() -> commands.confirm(user(), 999999L, "idem-noperm"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isForbidden);
    }

    @Test
    void missingLatestAttemptConflicts() {
        var noAttemptBatchId = insertBatch(organizationId, "READY_FOR_REVIEW");

        assertThatThrownBy(() -> commands.confirm(user(), noAttemptBatchId, "idem-no-attempt"))
                .isInstanceOf(DomainException.class)
                .satisfies(this::isStateConflict);
    }

    private void isStateConflict(Throwable throwable) {
        assertDomain(throwable, org.springframework.http.HttpStatus.CONFLICT, "STATE_CONFLICT");
    }

    private void isNotFound(Throwable throwable) {
        assertDomain(throwable, org.springframework.http.HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private void isForbidden(Throwable throwable) {
        assertDomain(throwable, org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    private void assertDomain(Throwable throwable, org.springframework.http.HttpStatus status, String code) {
        assertThat(throwable).isInstanceOf(DomainException.class);
        var exception = (DomainException) throwable;
        assertThat(exception.status()).isEqualTo(status);
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
                VALUES (?,?,'obj','confirm.csv','text/csv',1,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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

    private long insertAttempt(long batch, int attemptNo, String status, String trigger,
            Long predecessor, long errorCount) {
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,UTC_TIMESTAMP(6),NULL,NULL,0,0,0,?,UTC_TIMESTAMP(6))
                """, batch, attemptNo, status, trigger, predecessor, errorCount);
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
                WHERE r.code IN ('RETRYER','CANCELER','READER_ONLY')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('RETRYER','CANCELER','READER_ONLY')");
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
                VALUES (?,'Confirmer','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
