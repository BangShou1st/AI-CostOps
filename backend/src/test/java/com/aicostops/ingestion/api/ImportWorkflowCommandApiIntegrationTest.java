package com.aicostops.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=import-command-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ImportWorkflowCommandApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;
    private long failedBatchId;
    private long pendingBatchId;
    private long foreignBatchId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Command Api", "command-api");
        foreignOrganizationId = insertOrganization("Foreign", "command-api-foreign");
        actorUserId = insertUser("commander@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("RETRYER", List.of("IMPORT_RETRY"));
        createPermissionRole("CANCELER", List.of("IMPORT_CANCEL"));
        createPermissionRole("READER_ONLY", List.of("IMPORT_READ"));
        assign("RETRYER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        failedBatchId = insertBatch(organizationId, "FAILED");
        insertAttempt(failedBatchId, 1, "FAILED", "INITIAL", null);
        pendingBatchId = insertBatch(organizationId, "PENDING");
        insertAttempt(pendingBatchId, 1, "QUEUED", "INITIAL", null);
        foreignBatchId = insertBatch(foreignOrganizationId, "FAILED");
        insertAttempt(foreignBatchId, 1, "FAILED", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void retrySucceedsWithStringIdsAndStateChanges() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-retry-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.latestAttempt.id").isString())
                .andExpect(jsonPath("$.latestAttempt.attemptNo").value(2))
                .andExpect(jsonPath("$.latestAttempt.triggerType").value("MANUAL_RETRY"));

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, failedBatchId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void cancelSucceedsWithStringIdsAndStateChanges() throws Exception {
        assign("CANCELER", "ORG", organizationId);
        mockMvc.perform(post("/api/v1/imports/{importId}/cancel", pendingBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-cancel-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.latestAttempt.status").value("CANCELED"));

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, pendingBatchId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingOrBlankOrOverLengthKeyFailsWithoutAnyMutation() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));

        mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "k".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, failedBatchId)).isEqualTo("FAILED");
    }

    @Test
    void missingPermissionIsForbiddenBeforeResourceLookup() throws Exception {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign("READER_ONLY", "ORG", organizationId);

        mockMvc.perform(post("/api/v1/imports/{importId}/retry", 999999L)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-no-perm"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/imports/{importId}/cancel", 999999L)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-no-perm"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void permittedCallerWithCrossOrganizationIdIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/retry", foreignBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
    }

    @Test
    void illegalStateTransitionReturnsStateConflict() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/retry", pendingBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assign("CANCELER", "ORG", organizationId);
        mockMvc.perform(post("/api/v1/imports/{importId}/cancel", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-conflict-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void replayedSuccessfulKeyReturnsStoredSemanticResultWithoutSecondTransition() throws Exception {
        var first = mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-replay"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        var replay = mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-replay"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?", Integer.class,
                failedBatchId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
    }

    @Test
    void caseSensitiveRawKeysAreDistinctIdempotencyKeys() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "ABC"))
                .andExpect(status().isOk());

        // "abc" fingerprints differently from "ABC": it is a NEW key against a
        // now-PENDING batch and must conflict rather than replay.
        mockMvc.perform(post("/api/v1/imports/{importId}/retry", failedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "abc"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?", Integer.class,
                failedBatchId)).isEqualTo(2);
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
                WHERE r.code IN ('RETRYER','CANCELER','READER_ONLY')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('RETRYER','CANCELER','READER_ONLY')");
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
                VALUES (?,'Commander','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }
}
